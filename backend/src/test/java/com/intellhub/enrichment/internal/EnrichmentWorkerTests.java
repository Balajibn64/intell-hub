package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.ArticleEnrichment;
import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.ProviderException.Kind;
import com.intellhub.enrichment.SummarizerProvider;
import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.SummaryResult;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real Postgres, real services and a scripted provider: no test touches the network. The worker is driven
 * by calling tick() directly, so there is no waiting on a scheduler.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@ExtendWith(OutputCaptureExtension.class)
class EnrichmentWorkerTests {

	private static final Instant NOON = Instant.parse("2026-11-12T12:00:00Z");

	/** A provider whose behaviour each test chooses; by default it behaves like the fake one. */
	static class Scripted implements SummarizerProvider {

		interface Behavior {

			List<SummaryResult> run(List<SummaryItem> batch, int callNumber) throws ProviderException;

		}

		final AtomicInteger calls = new AtomicInteger();

		final List<List<SummaryItem>> seen = new CopyOnWriteArrayList<>();

		volatile Behavior behavior;

		Scripted(Behavior behavior) {
			this.behavior = behavior;
		}

		@Override
		public String name() {
			return "scripted";
		}

		@Override
		public List<SummaryResult> summarize(List<SummaryItem> batch) throws ProviderException {
			int number = calls.incrementAndGet();
			seen.add(batch);
			return behavior.run(batch, number);
		}

	}

	private final ArticleEnrichment articles;

	private final com.intellhub.articles.EnrichmentRequests requests;

	private final Taxonomy taxonomy;

	private final JdbcClient jdbcClient;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final MutableClock clock = new MutableClock(NOON);

	private UUID source;

	private int counter;

	EnrichmentWorkerTests(ArticleEnrichment articles, com.intellhub.articles.EnrichmentRequests requests, Taxonomy taxonomy,
			JdbcClient jdbcClient, JdbcTemplate jdbc, TransactionTemplate transaction) {
		this.articles = articles;
		this.requests = requests;
		this.taxonomy = taxonomy;
		this.jdbcClient = jdbcClient;
		this.jdbc = jdbc;
		this.transaction = transaction;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from tags");
		jdbc.update("delete from llm_usage");
		clock.advance(Duration.between(clock.instant(), NOON));
		source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source,
				"https://example.com/" + source);
		counter = 0;
	}

	// ---- helpers ----

	private static EnrichmentProperties props(int budget, int reserved, int batch, int attempts) {
		return new EnrichmentProperties("fake", budget, reserved, batch, attempts, Duration.ofSeconds(30), Duration.ofMinutes(10), 20,
				ZoneId.of("UTC"), 5000, 0.15, false);
	}

	private Scripted fakeBehaviour() {
		FakeSummarizer fake = new FakeSummarizer(taxonomy);
		return new Scripted((batch, n) -> fake.summarize(batch));
	}

	private EnrichmentWorker worker(EnrichmentProperties props, SummarizerProvider... providers) {
		LlmBudget budget = new LlmBudget(jdbcClient, clock, props, transaction);
		ProviderChain chain = new ProviderChain(List.of(providers), budget);
		return new EnrichmentWorker(articles, new Triage(articles, taxonomy, props), chain, new EnrichmentValidator(), props, budget, clock);
	}

	/** A budget that records which part of the budget each call was charged to, then defers to the real one. */
	private EnrichmentWorker workerRecording(EnrichmentProperties props, List<BudgetPriority> charged, SummarizerProvider provider) {
		LlmBudget real = new LlmBudget(jdbcClient, clock, props, transaction);
		CallBudget recording = new CallBudget() {
			@Override
			public boolean tryReserve(BudgetPriority priority, String name) {
				charged.add(priority);
				return real.tryReserve(priority, name);
			}

			@Override
			public int remaining(BudgetPriority priority) {
				return real.remaining(priority);
			}

			@Override
			public void recordFailure(String name) {
				real.recordFailure(name);
			}
		};
		ProviderChain chain = new ProviderChain(List.of(provider), recording);
		return new EnrichmentWorker(articles, new Triage(articles, taxonomy, props), chain, new EnrichmentValidator(), props, recording, clock);
	}

	private UUID relevant(String marker) {
		return article("Kubernetes tips " + marker, "How to run a postgres database on kubernetes. " + marker, "PENDING", null);
	}

	private UUID irrelevant() {
		return article("Ten pasta recipes", "Dinner ideas for the whole family.", "PENDING", null);
	}

	private UUID article(String title, String excerpt, String status, Double score) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, excerpt, published_at, ingested_at,
				status, heuristic_score)
				values (?, ?, ?, ?, ?, ?, ?, now(), ?, ?)""", id, source, "https://example.com/a/" + id,
				String.format("%064x", 80_000L + (++counter)), title, excerpt,
				Timestamp.from(Instant.parse("2026-11-01T00:00:00Z").plusSeconds(counter)), status, score);
		return id;
	}

	private String status(UUID id) {
		return jdbc.queryForObject("select status from articles where id = ?", String.class, id);
	}

	private int attempts(UUID id) {
		return jdbc.queryForObject("select enrichment_attempts from articles where id = ?", Integer.class, id);
	}

	private int count(String status) {
		return jdbc.queryForObject("select count(*) from articles where status = ?", Integer.class, status);
	}

	private int providerCalls() {
		return jdbc.queryForObject("select coalesce(sum(calls), 0) from llm_usage", Integer.class);
	}

	private int providerFailures() {
		return jdbc.queryForObject("select coalesce(sum(failures), 0) from llm_usage", Integer.class);
	}

	// ---- the main flow ----

	@Test
	void relevantArticlesAreEnrichedAndIrrelevantOnesAreOnlyShownWithTheirExcerpt() {
		List<UUID> relevant = List.of(relevant("a"), relevant("b"), relevant("c"));
		UUID pasta = irrelevant();
		irrelevant();
		Scripted provider = fakeBehaviour();

		TickResult result = worker(props(250, 50, 5, 3), provider).tick();

		assertThat(result.ran()).isTrue();
		assertThat(result.triaged()).isEqualTo(5);
		assertThat(result.batches()).isEqualTo(1);
		assertThat(result.enriched()).isEqualTo(3);
		assertThat(result.stoppedBecause()).isNull();
		for (UUID id : relevant) {
			assertThat(status(id)).isEqualTo("ENRICHED");
			assertThat(jdbc.queryForObject("select summary from articles where id = ?", String.class, id)).isNotBlank();
			assertThat(jdbc.queryForObject("select enriched_by from articles where id = ?", String.class, id)).isEqualTo("scripted");
			assertThat(jdbc.queryForObject("select category from articles where id = ?", String.class, id)).isNotNull();
			assertThat(jdbc.queryForList("select t.name from article_tags a join tags t on t.id = a.tag_id where a.article_id = ?",
					String.class, id)).contains("kubernetes");
		}
		assertThat(status(pasta)).isEqualTo("EXCERPT_ONLY");
		assertThat(jdbc.queryForObject("select summary from articles where id = ?", String.class, pasta)).isNull();
		assertThat(providerCalls()).isEqualTo(1);
		assertThat(provider.calls.get()).isEqualTo(1);
	}

	@Test
	void whatTheProviderSeesIsOnlyPlainTitleAndExcerptWithAPositionIndex() {
		relevant("x");
		Scripted provider = fakeBehaviour();

		worker(props(250, 50, 5, 3), provider).tick();

		SummaryItem item = provider.seen.get(0).get(0);
		assertThat(item.index()).isZero();
		assertThat(item.title()).startsWith("Kubernetes tips");
		assertThat(item.text()).contains("postgres database");
	}

	@Test
	void articlesAreSentInBatchesOfTheConfiguredSize() {
		for (int i = 0; i < 12; i++) {
			relevant("n" + i);
		}
		Scripted provider = fakeBehaviour();

		TickResult result = worker(props(250, 50, 5, 3), provider).tick();

		assertThat(result.batches()).isEqualTo(3);
		assertThat(result.enriched()).isEqualTo(12);
		assertThat(provider.seen).extracting(List::size).containsExactly(5, 5, 2);
		assertThat(providerCalls()).isEqualTo(3);
		assertThat(count("ENRICHED")).isEqualTo(12);
	}

	@Test
	void anEmptyQueueDoesNothingAndCostsNothing() {
		Scripted provider = fakeBehaviour();

		TickResult result = worker(props(250, 50, 5, 3), provider).tick();

		assertThat(result.ran()).isTrue();
		assertThat(result.batches()).isZero();
		assertThat(provider.calls.get()).isZero();
		assertThat(providerCalls()).isZero();
	}

	// ---- budget ----

	@Test
	void whenTheBudgetIsSpentTheRestStayQueuedMakeNoFurtherCallsAndResumeTomorrow() {
		for (int i = 0; i < 12; i++) {
			relevant("n" + i);
		}
		Scripted provider = fakeBehaviour();
		EnrichmentWorker worker = worker(props(2, 0, 5, 3), provider);

		TickResult first = worker.tick();

		assertThat(first.enriched()).isEqualTo(10);
		assertThat(first.stoppedBecause()).contains("budget");
		assertThat(provider.calls.get()).isEqualTo(2);
		assertThat(count("PENDING")).isEqualTo(2);
		assertThat(count("ENRICHING")).isZero();

		TickResult second = worker.tick();
		assertThat(second.enriched()).isZero();
		assertThat(provider.calls.get()).isEqualTo(2);
		assertThat(count("PENDING")).isEqualTo(2);

		clock.advance(Duration.ofHours(13)); // the next day
		TickResult tomorrow = worker.tick();
		assertThat(tomorrow.enriched()).isEqualTo(2);
		assertThat(count("ENRICHED")).isEqualTo(12);
	}

	@Test
	void aReadersRequestMaySpendTheReserveThatBackgroundWorkCannotTouch() {
		for (int i = 0; i < 4; i++) {
			relevant("n" + i);
		}
		Scripted provider = fakeBehaviour();
		EnrichmentWorker worker = worker(props(4, 2, 1, 3), provider);

		worker.tick(); // background work may use 2 of the 4 calls
		assertThat(providerCalls()).isEqualTo(2);
		assertThat(count("PENDING")).isEqualTo(2);

		UUID skipped1 = article("Ten pasta recipes", "dinner", "EXCERPT_ONLY", 0.0);
		UUID skipped2 = article("More pasta recipes", "lunch", "EXCERPT_ONLY", 0.0);
		UUID skipped3 = article("Even more pasta", "snacks", "EXCERPT_ONLY", 0.0);
		requests.requestEnrichment(skipped1);
		requests.requestEnrichment(skipped2);
		requests.requestEnrichment(skipped3);

		worker.tick();

		// The two reserved calls went to the readers' requests; the third request and the background work wait.
		assertThat(providerCalls()).isEqualTo(4);
		assertThat(status(skipped1)).isEqualTo("ENRICHED");
		assertThat(status(skipped2)).isEqualTo("ENRICHED");
		assertThat(status(skipped3)).isEqualTo("PENDING");
		assertThat(count("PENDING")).isEqualTo(3);
	}

	// ---- provider failures ----

	@Test
	void aProviderFailureHandsTheBatchBackAndBacksOffForAtLeastWhatTheProviderAsked() {
		for (int i = 0; i < 6; i++) {
			relevant("n" + i);
		}
		FakeSummarizer fake = new FakeSummarizer(taxonomy);
		Scripted provider = new Scripted((batch, n) -> {
			if (n == 1) {
				throw new ProviderException(Kind.RATE_LIMITED, "slow down", Duration.ofMinutes(2));
			}
			return fake.summarize(batch);
		});
		EnrichmentWorker worker = worker(props(250, 50, 5, 3), provider);

		TickResult failed = worker.tick();

		assertThat(failed.stoppedBecause()).contains("RATE_LIMITED");
		assertThat(failed.enriched()).isZero();
		assertThat(count("PENDING")).isEqualTo(6);
		assertThat(count("ENRICHING")).isZero();
		assertThat(providerCalls()).isEqualTo(1);
		assertThat(providerFailures()).isEqualTo(1);

		assertThat(worker.tick().ran()).isFalse();
		clock.advance(Duration.ofSeconds(119));
		assertThat(worker.tick().ran()).isFalse();
		assertThat(provider.calls.get()).isEqualTo(1);

		clock.advance(Duration.ofSeconds(2));
		TickResult recovered = worker.tick();
		assertThat(recovered.ran()).isTrue();
		assertThat(recovered.enriched()).isEqualTo(6);
		assertThat(count("ENRICHED")).isEqualTo(6);
	}

	@Test
	void backoffDoublesEachTimeInARowUpToFifteenMinutes() {
		relevant("only");
		Scripted provider = new Scripted((batch, n) -> {
			throw new ProviderException(Kind.UNAVAILABLE, "down");
		});
		EnrichmentWorker worker = worker(props(250, 50, 5, 100), provider);
		long[] expectedSeconds = { 30, 60, 120, 240, 480, 900, 900 };

		for (long expected : expectedSeconds) {
			assertThat(worker.tick().ran()).isTrue(); // this one runs and fails
			clock.advance(Duration.ofSeconds(expected - 1));
			assertThat(worker.tick().ran()).as("one second before " + expected).isFalse();
			clock.advance(Duration.ofSeconds(2));
		}
		assertThat(provider.calls.get()).isEqualTo(expectedSeconds.length);
	}

	@Test
	void aSuccessResetsTheBackoffToTheStart() {
		relevant("only");
		FakeSummarizer fake = new FakeSummarizer(taxonomy);
		Scripted provider = new Scripted((batch, n) -> {
			if (n <= 2) {
				throw new ProviderException(Kind.TIMEOUT, "slow");
			}
			return fake.summarize(batch);
		});
		EnrichmentWorker worker = worker(props(250, 50, 5, 100), provider);

		worker.tick(); // fails, 30 s
		clock.advance(Duration.ofSeconds(31));
		worker.tick(); // fails again, 60 s
		clock.advance(Duration.ofSeconds(61));
		assertThat(worker.tick().enriched()).isEqualTo(1); // succeeds

		relevant("second");
		provider.behavior = (batch, n) -> {
			throw new ProviderException(Kind.TIMEOUT, "slow");
		};
		worker.tick(); // fails: back to 30 s, not 120 s
		clock.advance(Duration.ofSeconds(31));
		assertThat(worker.tick().ran()).isTrue();
	}

	@Test
	void aBugInAProviderIsContainedHandsTheBatchBackAndStartsABackoff(CapturedOutput output) {
		relevant("LEAKMARK-1");
		Scripted provider = new Scripted((batch, n) -> {
			throw new IllegalStateException("exploded with LEAKMARK-SECRET-TEXT");
		});
		EnrichmentWorker worker = worker(props(250, 50, 5, 3), provider);

		TickResult result = worker.tick();

		assertThat(result.ran()).isTrue();
		assertThat(count("PENDING")).isEqualTo(1);
		assertThat(worker.tick().ran()).isFalse();
		assertThat(output.getAll()).doesNotContain("LEAKMARK");
	}

	// ---- bad output ----

	@Test
	void anItemTheProviderGetsWrongIsRetriedThenFailedAfterTheAttemptLimitWhileItsNeighbourSucceeds() {
		UUID good = relevant("good");
		UUID bad = relevant("bad");
		FakeSummarizer fake = new FakeSummarizer(taxonomy);
		Scripted provider = new Scripted((batch, n) -> {
			List<SummaryResult> results = new ArrayList<>(fake.summarize(batch));
			for (int i = 0; i < batch.size(); i++) {
				if (batch.get(i).title().contains("bad")) {
					results.set(i, new SummaryResult(i, "fine", null, List.of(), Double.NaN));
				}
			}
			return results;
		});
		EnrichmentWorker worker = worker(props(250, 50, 5, 3), provider);

		TickResult first = worker.tick();
		assertThat(first.enriched()).isEqualTo(1);
		assertThat(first.retried()).isEqualTo(1);
		assertThat(status(good)).isEqualTo("ENRICHED");
		assertThat(status(bad)).isEqualTo("PENDING");
		assertThat(attempts(bad)).isEqualTo(1);

		assertThat(worker.tick().retried()).isEqualTo(1);
		assertThat(attempts(bad)).isEqualTo(2);

		TickResult third = worker.tick();
		assertThat(third.failed()).isEqualTo(1);
		assertThat(status(bad)).isEqualTo("FAILED");
		assertThat(attempts(bad)).isEqualTo(3);
		assertThat(provider.calls.get()).isEqualTo(3);

		assertThat(worker.tick().batches()).isZero(); // nothing left to do
	}

	@Test
	void itemsTheProviderLeavesOutAreRetriedToo() {
		UUID answered = relevant("a");
		UUID missed = relevant("b");
		FakeSummarizer fake = new FakeSummarizer(taxonomy);
		Scripted provider = new Scripted((batch, n) -> {
			List<SummaryResult> all = fake.summarize(batch);
			// Answer only the first of several; a lone leftover item gets no answer at all.
			return batch.size() > 1 ? all.subList(0, 1) : List.of();
		});
		EnrichmentWorker worker = worker(props(250, 50, 5, 2), provider);

		worker.tick();
		worker.tick();

		assertThat(List.of(status(answered), status(missed))).containsExactlyInAnyOrder("ENRICHED", "FAILED");
	}

	@Test
	void hostileProviderOutputIsReducedToSafeBoundedValuesBeforeItIsStored() {
		UUID id = relevant("target");
		List<String> hostileTags = new ArrayList<>(List.of("kubernetes", "'; drop table tags; --", "<script>", "UPPER", "has space",
				"../../etc", "a".repeat(40)));
		for (int i = 0; i < 40; i++) {
			hostileTags.add("extra" + i);
		}
		Scripted provider = new Scripted((batch, n) -> List.of(new SummaryResult(0,
				"Great! Visit https://evil.example/phish now <script>alert(1)</script>‮ reversed​", "SPORTS", hostileTags, 0.9)));

		worker(props(250, 50, 5, 3), provider).tick();

		assertThat(status(id)).isEqualTo("ENRICHED");
		String summary = jdbc.queryForObject("select summary from articles where id = ?", String.class, id);
		assertThat(summary).doesNotContain("evil.example").doesNotContain("‮").doesNotContain("​");
		assertThat(summary).contains("<script>alert(1)</script>"); // kept as text; it is never rendered as HTML
		assertThat(jdbc.queryForObject("select category from articles where id = ?", String.class, id)).isNull();
		List<String> tags = jdbc.queryForList("select t.name from article_tags a join tags t on t.id = a.tag_id where a.article_id = ?",
				String.class, id);
		assertThat(tags).hasSizeLessThanOrEqualTo(5).allMatch(t -> t.matches("^[a-z0-9][a-z0-9+#.-]{0,29}$"));
		assertThat(jdbc.queryForObject("select count(*) from tags where name like '%drop%' or name like '%script%'", Integer.class)).isZero();
	}

	@Test
	void freeTagsStopWhenTheTableCapIsReached() {
		relevant("target");
		EnrichmentProperties capped = new EnrichmentProperties("fake", 250, 50, 5, 3, Duration.ofSeconds(30), Duration.ofMinutes(10), 20,
				ZoneId.of("UTC"), 2, 0.15, false);
		Scripted provider = new Scripted((batch, n) -> List.of(new SummaryResult(0, "ok", null, List.of("one", "two", "three", "four"), 0.5)));

		worker(capped, provider).tick();

		assertThat(jdbc.queryForObject("select count(*) from tags where kind = 'FREE'", Integer.class)).isEqualTo(2);
	}

	// ---- crashes, restarts, retries ----

	@Test
	void workLeftEnrichingByACrashIsTakenBackAndFinishedButARecentClaimIsLeftAlone() {
		UUID stale1 = article("Kubernetes one", "kubernetes postgres", "ENRICHING", 0.8);
		UUID stale2 = article("Kubernetes two", "kubernetes postgres", "ENRICHING", 0.8);
		UUID recent = article("Kubernetes three", "kubernetes postgres", "ENRICHING", 0.8);
		jdbc.update("update articles set claimed_at = now() - interval '30 minutes' where id in (?, ?)", stale1, stale2);
		jdbc.update("update articles set claimed_at = now() - interval '1 minute' where id = ?", recent);

		TickResult result = worker(props(250, 50, 5, 3), fakeBehaviour()).tick();

		assertThat(result.recovered()).isEqualTo(2);
		assertThat(status(stale1)).isEqualTo("ENRICHED");
		assertThat(status(stale2)).isEqualTo("ENRICHED");
		assertThat(status(recent)).isEqualTo("ENRICHING");
	}

	@Test
	void anArticleThatFailedYesterdayGetsAnotherChance() {
		UUID failed = article("Kubernetes failed", "kubernetes postgres", "FAILED", 0.8);
		jdbc.update("update articles set claimed_at = now() - interval '25 hours', enrichment_attempts = 3 where id = ?", failed);

		TickResult result = worker(props(250, 50, 5, 3), fakeBehaviour()).tick();

		assertThat(result.recovered()).isEqualTo(1);
		assertThat(status(failed)).isEqualTo("ENRICHED");
		assertThat(attempts(failed)).isEqualTo(1);
	}

	// ---- when it should not run ----

	@Test
	void withNoProviderNothingHappensAtAll() {
		UUID id = relevant("a");
		EnrichmentWorker worker = worker(props(250, 50, 5, 3));

		TickResult result = worker.tick();

		assertThat(result.ran()).isFalse();
		assertThat(result.stoppedBecause()).contains("no provider");
		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select heuristic_score from articles where id = ?", Double.class, id)).isNull();
		assertThat(providerCalls()).isZero();
	}

	@Test
	void aRunThatOverlapsAnotherIsSkipped() throws Exception {
		relevant("a");
		CountDownLatch inProvider = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		FakeSummarizer fake = new FakeSummarizer(taxonomy);
		Scripted provider = new Scripted((batch, n) -> {
			inProvider.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return fake.summarize(batch);
		});
		EnrichmentWorker worker = worker(props(250, 50, 5, 3), provider);

		CompletableFuture<TickResult> first = CompletableFuture.supplyAsync(worker::tick);
		assertThat(inProvider.await(10, TimeUnit.SECONDS)).isTrue();

		TickResult overlapping = worker.tick();
		release.countDown();

		assertThat(overlapping.ran()).isFalse();
		assertThat(overlapping.stoppedBecause()).contains("another run");
		assertThat(first.get(10, TimeUnit.SECONDS).enriched()).isEqualTo(1);
		assertThat(provider.calls.get()).isEqualTo(1);
	}

	@Test
	void nothingFromAnArticleOrAModelIsEverLogged(CapturedOutput output) {
		String marker = "LEAKMARK-7e41c9";
		relevant(marker);
		article("Pasta " + marker, "recipe " + marker, "PENDING", null);
		Scripted provider = new Scripted((batch, n) -> List.of(new SummaryResult(0, "Summary with " + marker, null, List.of("tag-" + marker.toLowerCase()), 0.5)));

		worker(props(250, 50, 5, 3), provider).tick();

		assertThat(output.getAll()).contains("Enrichment run").doesNotContain(marker).doesNotContain(marker.toLowerCase());
	}

	// ---- an outage is not the article's fault ----

	@Test
	void aLongProviderOutageDoesNotUseUpAnArticlesRetries() {
		UUID id = relevant("patient");
		FakeSummarizer fake = new FakeSummarizer(taxonomy);
		Scripted provider = new Scripted((batch, n) -> {
			throw new ProviderException(Kind.UNAVAILABLE, "down");
		});
		EnrichmentWorker worker = worker(props(250, 50, 5, 3), provider);

		for (int outage = 0; outage < 6; outage++) {
			assertThat(worker.tick().ran()).isTrue();
			clock.advance(Duration.ofMinutes(16)); // past any backoff
		}
		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(attempts(id)).isZero();

		// The provider comes back but gets this one article wrong once: that is a first attempt, not the last.
		provider.behavior = (batch, n) -> List.of(new SummaryResult(0, "fine", null, List.of(), Double.NaN));
		worker.tick();
		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(attempts(id)).isEqualTo(1);

		provider.behavior = (batch, n) -> fake.summarize(batch);
		assertThat(worker.tick().enriched()).isEqualTo(1);
		assertThat(status(id)).isEqualTo("ENRICHED");
	}

	@Test
	void aProviderBugOrASpentBudgetAlsoLeavesAttemptsUntouched() {
		UUID id = relevant("budget");
		FakeSummarizer fake = new FakeSummarizer(taxonomy);
		Scripted provider = new Scripted((batch, n) -> fake.summarize(batch));
		EnrichmentWorker worker = worker(props(1, 0, 1, 3), provider);
		jdbc.update("insert into llm_usage (day, provider, calls, failures) values (?, 'other', 1, 0)", java.time.LocalDate.ofInstant(NOON, ZoneId.of("UTC")));

		worker.tick();

		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(attempts(id)).isZero();
		assertThat(provider.calls.get()).isZero();
	}

	@Test
	void whenTheBudgetIsSpentNothingIsClaimedOrWrittenOnLaterRuns() {
		for (int i = 0; i < 4; i++) {
			relevant("n" + i);
		}
		Scripted provider = fakeBehaviour();
		EnrichmentWorker worker = worker(props(1, 0, 2, 3), provider);
		worker.tick(); // uses the single call, enriches 2

		for (int run = 0; run < 5; run++) {
			TickResult result = worker.tick();
			assertThat(result.stoppedBecause()).contains("budget");
			assertThat(result.batches()).isZero();
		}

		// A claim would have set claimed_at and counted an attempt; the budget check comes first, so neither happened.
		assertThat(jdbc.queryForObject("select count(*) from articles where status = 'PENDING' and claimed_at is not null", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select coalesce(max(enrichment_attempts), 0) from articles where status = 'PENDING'", Integer.class)).isZero();
		assertThat(provider.calls.get()).isEqualTo(1);
	}

	// ---- readers' requests and background work are kept apart ----

	@Test
	void backgroundArticlesNeverSpendTheReserveEvenWhenTheyShareARunWithARequest() {
		for (int i = 0; i < 6; i++) {
			relevant("bg" + i);
		}
		UUID requested = article("Ten pasta recipes", "dinner", "EXCERPT_ONLY", 0.0);
		requests.requestEnrichment(requested);
		Scripted provider = fakeBehaviour();
		EnrichmentWorker worker = worker(props(4, 2, 5, 3), provider);

		TickResult first = worker.tick();

		// One call for the request (reserve), one call for five background articles (normal cap is 2 of 4),
		// and then the normal budget is used up, so the sixth background article waits.
		assertThat(status(requested)).isEqualTo("ENRICHED");
		assertThat(first.enriched()).isEqualTo(6);
		assertThat(count("PENDING")).isEqualTo(1);
		assertThat(providerCalls()).isEqualTo(2);
		assertThat(provider.seen.get(0)).hasSize(1); // the request went alone, as its own batch

		// Because background work could not take the reserve, it is still there for readers.
		UUID another = article("More pasta recipes", "lunch", "EXCERPT_ONLY", 0.0);
		requests.requestEnrichment(another);
		worker.tick();
		assertThat(status(another)).isEqualTo("ENRICHED");
		assertThat(providerCalls()).isEqualTo(3);
		assertThat(count("PENDING")).isEqualTo(1);
	}

	@Test
	void requestsAreServedEvenWhenTheNormalBudgetIsGone() {
		relevant("bg");
		Scripted provider = fakeBehaviour();
		EnrichmentWorker worker = worker(props(3, 2, 5, 3), provider);
		worker.tick(); // the one normal call enriches the background article
		UUID requested = article("Ten pasta recipes", "dinner", "EXCERPT_ONLY", 0.0);
		requests.requestEnrichment(requested);

		worker.tick();

		assertThat(status(requested)).isEqualTo("ENRICHED");
		assertThat(providerCalls()).isEqualTo(2);
	}

	// ---- where tags come from ----

	@Test
	void tagsFromTheKeywordProviderAreLabelledHeuristicAndModelTagsAreLabelledLlm() {
		UUID fromFake = relevant("fake");
		worker(props(250, 50, 5, 3), new FakeSummarizer(taxonomy)).tick();
		assertThat(jdbc.queryForList("select distinct origin from article_tags where article_id = ?", String.class, fromFake))
				.containsExactly("HEURISTIC");

		UUID fromModel = relevant("model");
		Scripted model = new Scripted((batch, n) -> List.of(new SummaryResult(0, "ok", null, List.of("kubernetes"), 0.5)));
		worker(props(250, 50, 5, 3), model).tick();
		assertThat(jdbc.queryForList("select distinct origin from article_tags where article_id = ?", String.class, fromModel))
				.containsExactly("LLM");
	}

	@Test
	void eachBatchIsChargedToThePartOfTheBudgetItIsAllowedToUse() {
		relevant("bg1");
		relevant("bg2");
		UUID requested = article("Ten pasta recipes", "dinner", "EXCERPT_ONLY", 0.0);
		requests.requestEnrichment(requested);
		List<BudgetPriority> charged = new ArrayList<>();

		workerRecording(props(10, 3, 1, 3), charged, fakeBehaviour()).tick();

		// The reader's request is charged to the reserve; the background articles are not allowed to be.
		assertThat(charged).containsExactly(BudgetPriority.RESERVED, BudgetPriority.NORMAL, BudgetPriority.NORMAL);
		assertThat(count("ENRICHED")).isEqualTo(3);
	}

}
