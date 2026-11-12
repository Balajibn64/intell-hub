package com.intellhub.enrichment.internal;

import com.intellhub.articles.AppliedEnrichment;
import com.intellhub.articles.ArticleEnrichment;
import com.intellhub.articles.ClaimKind;
import com.intellhub.articles.EnrichmentInput;
import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.internal.EnrichmentValidator.Accepted;
import com.intellhub.enrichment.internal.EnrichmentValidator.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns waiting articles into enriched ones, a few at a time.
 *
 * <p>One {@link #tick()}: take back work a crash left behind, run the cheap pre-filter, then claim small
 * batches, spend budget, ask the provider chain, validate everything that comes back and store only what
 * passes. Readers' requests are claimed first and as their own batches, because only they may spend the
 * reserved part of the budget; background work comes after and may not. The budget is checked before
 * anything is claimed. When the provider fails the run stops and backs off; when the budget is spent it stops
 * and leaves the rest queued.
 *
 * <p>An article is only charged an attempt when the provider got it wrong. If the provider was down, the
 * budget ran out or something broke, the claim is handed back with the attempt refunded.
 *
 * <p>Logs carry counts and exception class names only, never article text or anything a model returned.
 */
@Component
class EnrichmentWorker {

	private static final Logger log = LoggerFactory.getLogger(EnrichmentWorker.class);

	private static final int TRIAGE_PAGE = 200;

	private static final int MAX_TRIAGE_PAGES = 5;

	private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

	private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

	private static final Duration FAILED_RETRY_AFTER = Duration.ofDays(1);

	private final ArticleEnrichment articles;

	private final Triage triage;

	private final ProviderChain chain;

	private final EnrichmentValidator validator;

	private final EnrichmentProperties properties;

	private final CallBudget budget;

	private final Clock clock;

	private final AtomicBoolean running = new AtomicBoolean();

	private volatile Instant backoffUntil = Instant.MIN;

	private int consecutiveFailures;

	private LocalDate budgetLoggedOn;

	EnrichmentWorker(ArticleEnrichment articles, Triage triage, ProviderChain chain, EnrichmentValidator validator,
			EnrichmentProperties properties, CallBudget budget, Clock clock) {
		this.articles = articles;
		this.triage = triage;
		this.chain = chain;
		this.validator = validator;
		this.properties = properties;
		this.budget = budget;
		this.clock = clock;
	}

	/** One run. Safe to call from several threads: a call that overlaps another is skipped. */
	TickResult tick() {
		if (!chain.hasProviders()) {
			return TickResult.notRun("no provider is configured");
		}
		if (!running.compareAndSet(false, true)) {
			return TickResult.notRun("another run is in progress");
		}
		try {
			if (clock.instant().isBefore(backoffUntil)) {
				return TickResult.notRun("backing off after a provider failure");
			}
			return doTick();
		}
		finally {
			running.set(false);
		}
	}

	private TickResult doTick() {
		ArticleEnrichment.Recovered recovered = articles.recoverStale(properties.staleAfter(), FAILED_RETRY_AFTER);
		int triaged = 0;
		for (int page = 0; page < MAX_TRIAGE_PAGES; page++) {
			int handled = triage.run(TRIAGE_PAGE);
			triaged += handled;
			if (handled < TRIAGE_PAGE) {
				break;
			}
		}

		int batches = 0;
		int enriched = 0;
		String stoppedBecause = null;
		// Articles the provider got wrong stay claimed until the end of the run, so they are not picked up again
		// straight away and cannot burn all their attempts (and budget) in a few seconds.
		List<UUID> toRetry = new ArrayList<>();
		List<UUID> toFail = new ArrayList<>();
		try {
			while (batches < properties.maxBatchesPerTick()) {
				if (budget.remaining(BudgetPriority.RESERVED) <= 0) {
					logBudgetSpentOncePerDay();
					stoppedBecause = "the daily budget is spent";
					break;
				}
				// Readers' requests first, as their own batch: only they may use the reserved budget.
				BudgetPriority priority = BudgetPriority.RESERVED;
				List<EnrichmentInput> claimed = articles.claimBatch(properties.batchSize(), ClaimKind.REQUESTED);
				if (claimed.isEmpty() && budget.remaining(BudgetPriority.NORMAL) > 0) {
					priority = BudgetPriority.NORMAL;
					claimed = articles.claimBatch(properties.batchSize(), ClaimKind.BACKGROUND);
				}
				if (claimed.isEmpty()) {
					break;
				}
				batches++;
				try {
					enriched += process(claimed, priority, toRetry, toFail);
				}
				catch (BudgetExhaustedException e) {
					// Someone else took the last call between the check and the claim.
					handBack(claimed);
					logBudgetSpentOncePerDay();
					stoppedBecause = "the daily budget is spent";
					break;
				}
				catch (ProviderException e) {
					handBack(claimed);
					startBackoff(e);
					stoppedBecause = "the provider failed: " + e.getKind();
					break;
				}
				catch (RuntimeException e) {
					// Whatever went wrong, hand the batch back and stop; the message could hold article text.
					handBack(claimed);
					log.warn("Enrichment batch failed unexpectedly: {}", e.getClass().getSimpleName());
					stoppedBecause = "an unexpected error";
					break;
				}
				consecutiveFailures = 0;
				backoffUntil = Instant.MIN;
			}
		}
		finally {
			articles.release(toRetry);
			articles.fail(toFail);
		}
		int retried = toRetry.size();
		int failed = toFail.size();
		TickResult tick = new TickResult(true, recovered.staleClaims() + recovered.retriedFailures(), triaged, batches, enriched,
				retried, failed, stoppedBecause);
		if (batches > 0 || triaged > 0 || tick.recovered() > 0) {
			log.info("Enrichment run: {} recovered, {} triaged, {} batches, {} enriched, {} to retry, {} failed{}", tick.recovered(),
					triaged, batches, enriched, retried, failed, stoppedBecause == null ? "" : ", stopped: " + stoppedBecause);
		}
		return tick;
	}

	/** Returns how many articles were enriched; adds the ones to retry or fail to the given lists. */
	private int process(List<EnrichmentInput> claimed, BudgetPriority priority, List<UUID> toRetry, List<UUID> toFail)
			throws ProviderException, BudgetExhaustedException {
		List<SummaryItem> items = new ArrayList<>();
		for (int i = 0; i < claimed.size(); i++) {
			items.add(new SummaryItem(i, claimed.get(i).title(), claimed.get(i).excerpt()));
		}
		ProviderChain.Answer answer = chain.summarize(items, priority);
		Outcome outcome = validator.validate(items, answer.results());

		List<AppliedEnrichment> applied = new ArrayList<>();
		for (Map.Entry<Integer, Accepted> entry : outcome.accepted().entrySet()) {
			Accepted accepted = entry.getValue();
			applied.add(new AppliedEnrichment(claimed.get(entry.getKey()).id(), accepted.summary(), accepted.category(),
					accepted.relevance(), accepted.tags(), answer.tagOrigin(), answer.provider()));
		}
		articles.applyResults(applied, properties.maxFreeTags());

		for (int index : outcome.rejected()) {
			EnrichmentInput input = claimed.get(index);
			(input.attempts() >= properties.maxAttempts() ? toFail : toRetry).add(input.id());
		}
		return applied.size();
	}

	/** Gives the claim back and refunds the attempt: the article was not at fault. */
	private void handBack(List<EnrichmentInput> claimed) {
		articles.releaseWithoutAttempt(claimed.stream().map(EnrichmentInput::id).toList());
	}

	/** 30 seconds, doubling each time in a row, up to 15 minutes; a longer retry-after from the provider wins. */
	private void startBackoff(ProviderException e) {
		consecutiveFailures++;
		long seconds = BASE_BACKOFF.toSeconds() << Math.min(consecutiveFailures - 1, 10);
		Duration delay = Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
		if (e.getRetryAfter() != null && e.getRetryAfter().compareTo(delay) > 0) {
			delay = e.getRetryAfter().compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : e.getRetryAfter();
		}
		backoffUntil = clock.instant().plus(delay);
		log.warn("Enrichment provider failed ({}); backing off for {} seconds", e.getKind(), delay.toSeconds());
	}

	private void logBudgetSpentOncePerDay() {
		LocalDate today = LocalDate.ofInstant(clock.instant(), properties.budgetZone());
		if (!today.equals(budgetLoggedOn)) {
			budgetLoggedOn = today;
			log.info("The daily enrichment budget is spent; any remaining articles stay queued until tomorrow");
		}
	}

}
