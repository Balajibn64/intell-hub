package com.intellhub.retention.internal;

import com.intellhub.articles.ArticleRetention;
import com.intellhub.identity.TokenMaintenance;
import com.intellhub.personalization.ReadingState;
import com.intellhub.retention.RetentionResult;
import com.intellhub.retention.RetentionRunningException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes articles past the retention window, except any a user has saved, and expired refresh tokens.
 *
 * <p>Each batch is one transaction: the expired articles are locked, the module that owns saved articles says
 * which of them are saved, and the rest are deleted. The lock is what makes this safe against a user saving an
 * article at the same moment: the save waits for the batch to finish, then either finds the article gone (like any
 * missing article) or, if it committed first, was already seen here and the article was kept.
 *
 * <p>Nothing about an article (title, address, text) is ever logged, only counts.
 */
@Service
class RetentionService {

	private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

	private record Batch(List<UUID> ids, int kept, int deleted) {
	}

	private final ArticleRetention articles;

	private final ReadingState readingState;

	private final TokenMaintenance tokens;

	private final RetentionProperties properties;

	private final Clock clock;

	private final TransactionTemplate transaction;

	private final AtomicBoolean running = new AtomicBoolean();

	private volatile RetentionResult lastRun;

	RetentionService(ArticleRetention articles, ReadingState readingState, TokenMaintenance tokens, RetentionProperties properties,
			Clock clock, PlatformTransactionManager transactionManager) {
		this.articles = articles;
		this.readingState = readingState;
		this.tokens = tokens;
		this.properties = properties;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/** The result of the most recent real (not dry) run since the app started, if any. */
	Optional<RetentionResult> lastRun() {
		return Optional.ofNullable(lastRun);
	}

	RetentionProperties properties() {
		return properties;
	}

	/** @throws RetentionRunningException if another run is in progress */
	RetentionResult run(boolean dryRun) {
		if (!running.compareAndSet(false, true)) {
			throw new RetentionRunningException();
		}
		try {
			return doRun(dryRun);
		}
		finally {
			running.set(false);
		}
	}

	private RetentionResult doRun(boolean dryRun) {
		long started = System.nanoTime();
		Instant now = clock.instant();
		Instant cutoff = now.minus(properties.window());
		// Tokens first: it is one cheap statement and does not depend on the articles, so a failing article batch can
		// never keep expired tokens from being cleaned up night after night.
		long tokensDeleted = dryRun ? 0 : tokens.purgeExpiredRefreshTokens(now.minus(properties.tokenGrace()));
		long expired = 0;
		long kept = 0;
		long deleted = 0;
		UUID after = null;
		try {
			while (true) {
				UUID cursor = after;
				Batch batch = transaction.execute(status -> purgeBatch(cutoff, cursor, dryRun));
				if (batch.ids().isEmpty()) {
					break;
				}
				expired += batch.ids().size();
				kept += batch.kept();
				deleted += batch.deleted();
				after = batch.ids().get(batch.ids().size() - 1);
				if (batch.ids().size() < properties.batchSize()) {
					break;
				}
			}
		}
		catch (RuntimeException e) {
			// Earlier batches are already committed; say how far the run got (counts only), then let it fail.
			log.warn("Retention {} failed after {} expired articles looked at, {} deleted, {} expired tokens deleted: {}",
					dryRun ? "dry run" : "purge", expired, deleted, tokensDeleted, e.getClass().getSimpleName());
			throw e;
		}
		RetentionResult result = new RetentionResult(dryRun, expired, kept, deleted, tokensDeleted,
				(System.nanoTime() - started) / 1_000_000, clock.instant());
		if (!dryRun) {
			lastRun = result; // a dry run must not hide what the last real purge did
		}
		log.info("Retention {}: {} expired articles, {} kept because saved, {} deleted, {} expired tokens deleted, {} ms",
				dryRun ? "dry run" : "purge", expired, kept, deleted, tokensDeleted, result.durationMillis());
		return result;
	}

	private Batch purgeBatch(Instant cutoff, UUID after, boolean dryRun) {
		// A dry run only reads: it must not lock articles and make a reader's save wait.
		List<UUID> ids = dryRun ? articles.listExpired(cutoff, after, properties.batchSize())
				: articles.lockExpired(cutoff, after, properties.batchSize());
		if (ids.isEmpty()) {
			return new Batch(ids, 0, 0);
		}
		Set<UUID> saved = readingState.savedByAnyone(ids);
		List<UUID> toDelete = new ArrayList<>();
		for (UUID id : ids) {
			if (!saved.contains(id)) {
				toDelete.add(id);
			}
		}
		int deleted = dryRun ? 0 : articles.delete(toDelete);
		return new Batch(ids, saved.size(), deleted);
	}

}
