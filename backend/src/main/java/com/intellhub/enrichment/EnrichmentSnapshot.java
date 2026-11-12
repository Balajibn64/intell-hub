package com.intellhub.enrichment;

import java.time.LocalDate;
import java.util.List;

/**
 * How enrichment stands right now.
 *
 * @param enabled whether a provider is configured (otherwise articles simply stay pending)
 * @param providers the providers in the order they are tried
 * @param day the budget day this is about (it starts at midnight in the budget time zone)
 * @param dailyBudget provider calls allowed per day across all providers
 * @param reservedCalls how many of those only a reader's request may use
 * @param usedToday calls made today
 * @param remainingForReaders calls a reader's request can still use today
 * @param remainingForBackground calls background work can still use today (the reserve excluded)
 * @param usageToday calls and failures per provider today
 */
public record EnrichmentSnapshot(boolean enabled, List<String> providers, LocalDate day, int dailyBudget, int reservedCalls,
		int usedToday, int remainingForReaders, int remainingForBackground, List<ProviderUsage> usageToday) {

	/** One provider's calls and failed calls today. */
	public record ProviderUsage(String provider, int calls, int failures) {
	}

}
