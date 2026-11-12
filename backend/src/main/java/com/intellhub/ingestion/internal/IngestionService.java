package com.intellhub.ingestion.internal;

import com.intellhub.articles.ArticleStore;
import com.intellhub.articles.NewArticle;
import com.intellhub.articles.StoreOutcome;
import com.intellhub.ingestion.internal.IngestSummary.SourceResult;
import com.intellhub.ingestion.internal.IngestSummary.Status;
import com.intellhub.sources.FetchResult;
import com.intellhub.sources.SourceCatalog;
import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs ingestion: fetch each enabled RSS source, parse and clean its entries, store what is new, and
 * report each source's health.
 *
 * <p>Sources are independent: one that fails, hangs or misbehaves never stops the others. Everything that
 * comes from a feed is untrusted, so nothing from a feed (titles, URLs, bodies) is ever logged, and the
 * only error texts that are stored or returned are fixed strings.
 */
@Service
class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	private static final Duration FUTURE_TOLERANCE = Duration.ofDays(1);

	private final SourceCatalog sources;

	private final ArticleStore articles;

	private final SafeHttpFetcher fetcher;

	private final FeedParser parser;

	private final ContentSanitizer sanitizer;

	private final IngestionProperties properties;

	private final Clock clock;

	private final AtomicBoolean running = new AtomicBoolean();

	IngestionService(SourceCatalog sources, ArticleStore articles, SafeHttpFetcher fetcher, FeedParser parser,
			ContentSanitizer sanitizer, IngestionProperties properties, Clock clock) {
		this.sources = sources;
		this.articles = articles;
		this.fetcher = fetcher;
		this.parser = parser;
		this.sanitizer = sanitizer;
		this.properties = properties;
		this.clock = clock;
	}

	/** @throws IngestAlreadyRunningException if a run is already in progress */
	IngestSummary run() {
		if (!running.compareAndSet(false, true)) {
			throw new IngestAlreadyRunningException();
		}
		try {
			return doRun();
		}
		finally {
			running.set(false);
		}
	}

	private IngestSummary doRun() {
		Instant started = clock.instant();
		long startNanos = System.nanoTime();
		List<SourceInfo> enabled = sources.enabledSources();
		List<SourceResult> results = new ArrayList<>();

		ExecutorService pool = Executors.newFixedThreadPool(properties.concurrency());
		try {
			List<Callable<SourceResult>> tasks = new ArrayList<>();
			for (SourceInfo source : enabled) {
				tasks.add(() -> processSource(source));
			}
			List<Future<SourceResult>> futures = pool.invokeAll(tasks, properties.runTimeout().toMillis(),
					TimeUnit.MILLISECONDS);
			for (int i = 0; i < futures.size(); i++) {
				results.add(resultOf(enabled.get(i), futures.get(i)));
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			for (SourceInfo source : enabled) {
				results.add(SourceResult.failed(source.id(), source.name(), "Run interrupted"));
			}
		}
		finally {
			pool.shutdownNow();
		}

		IngestSummary summary = summarize(started, Duration.ofNanos(System.nanoTime() - startNanos), results);
		log.info("Ingestion finished: {} sources ({} ok, {} failed, {} skipped), {} new articles, {} duplicates, {} ms",
				summary.sourcesTotal(), summary.sourcesOk(), summary.sourcesFailed(), summary.sourcesSkipped(),
				summary.articlesCreated(), summary.duplicates(), summary.durationMillis());
		return summary;
	}

	private SourceResult resultOf(SourceInfo source, Future<SourceResult> future) {
		if (future.isCancelled()) {
			// Not the source's fault, so its health record is left alone.
			return SourceResult.failed(source.id(), source.name(), "Run time limit reached");
		}
		try {
			return future.get();
		}
		catch (Exception e) {
			return SourceResult.failed(source.id(), source.name(), "Unexpected error");
		}
	}

	private SourceResult processSource(SourceInfo source) {
		if (source.type() != SourceType.RSS) {
			return SourceResult.unsupported(source.id(), source.name());
		}
		try {
			return ingest(source);
		}
		catch (RuntimeException e) {
			// Only the class name is logged: the message could contain text from the feed.
			log.warn("Ingestion of source {} failed unexpectedly: {}", source.id(), e.getClass().getSimpleName());
			return fail(source, "Unexpected error");
		}
	}

	private SourceResult ingest(SourceInfo source) {
		FetchOutcome outcome = fetcher.fetch(source.url());
		if (outcome instanceof FetchOutcome.Failure failure) {
			return fail(source, failure.message());
		}
		byte[] body = ((FetchOutcome.Success) outcome).body();
		Instant now = clock.instant();
		ParsedFeed feed;
		try {
			feed = parser.parse(body, new ParseLimits(properties.maxEntries(), now.minus(properties.maxAge())));
		}
		catch (FeedParseException e) {
			return fail(source, "Invalid feed");
		}
		int created = 0;
		int duplicates = 0;
		int skipped = feed.skippedInvalid() + feed.skippedOld();
		int errors = 0;
		for (ParsedEntry entry : feed.entries()) {
			try {
				NewArticle article = toArticle(entry, source.id(), now);
				if (article == null) {
					skipped++;
					continue;
				}
				StoreOutcome stored = articles.store(article);
				if (stored == StoreOutcome.CREATED) {
					created++;
				}
				else {
					duplicates++;
				}
			}
			catch (RuntimeException e) {
				errors++;
			}
		}
		sources.recordFetchResult(source.id(), FetchResult.ok());
		return new SourceResult(source.id(), source.name(), Status.OK, null, feed.entries().size(), created, duplicates,
				skipped, errors);
	}

	private SourceResult fail(SourceInfo source, String reason) {
		sources.recordFetchResult(source.id(), FetchResult.failed(reason));
		return SourceResult.failed(source.id(), source.name(), reason);
	}

	/** Cleans one entry. Returns null if it cannot be used. */
	private NewArticle toArticle(ParsedEntry entry, UUID sourceId, Instant now) {
		String canonicalUrl = UrlCanonicalizer.canonicalize(entry.link());
		String title = sanitizer.title(entry.title());
		if (canonicalUrl == null || title.isBlank()) {
			return null;
		}
		String plainText = sanitizer.toPlainText(entry.contentHtml());
		Instant published = entry.publishedAt();
		if (published == null || published.isAfter(now.plus(FUTURE_TOLERANCE))) {
			published = now;
		}
		return new NewArticle(sourceId, canonicalUrl, ContentHasher.hash(title, plainText, canonicalUrl), title,
				sanitizer.author(entry.author()), published, sanitizer.excerpt(plainText),
				sanitizer.sanitizeHtml(entry.contentHtml(), canonicalUrl));
	}

	private static IngestSummary summarize(Instant started, Duration took, List<SourceResult> results) {
		int ok = 0;
		int failed = 0;
		int skippedSources = 0;
		int created = 0;
		int duplicates = 0;
		int skipped = 0;
		int errors = 0;
		for (SourceResult r : results) {
			switch (r.status()) {
				case OK -> ok++;
				case FAILED -> failed++;
				case SKIPPED_UNSUPPORTED -> skippedSources++;
			}
			created += r.created();
			duplicates += r.duplicates();
			skipped += r.skipped();
			errors += r.errors();
		}
		return new IngestSummary(started, took.toMillis(), results.size(), ok, failed, skippedSources, created,
				duplicates, skipped, errors, List.copyOf(results));
	}

}
