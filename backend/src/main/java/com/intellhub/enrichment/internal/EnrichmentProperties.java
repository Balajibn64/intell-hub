package com.intellhub.enrichment.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for enrichment. With no provider set the whole pipeline is off and articles stay PENDING.
 *
 * @param provider the providers to use, in the order to try them, separated by commas and no spaces: {@code gemini},
 * {@code groq} or {@code fake} (offline, never together with a real one); unset or blank means off
 * @param dailyBudget most provider calls per day across all providers
 * @param reservedCalls how many of those only requests from readers may use
 * @param batchSize articles per provider call
 * @param maxAttempts tries before an article is FAILED
 * @param pollInterval pause between worker runs
 * @param staleAfter how long an ENRICHING claim may sit before it is taken back
 * @param maxBatchesPerTick most batches one worker run handles
 * @param budgetZone the time zone whose midnight starts a new day of budget
 * @param maxFreeTags most free tags that may exist
 * @param triageThreshold pre-filter score below which an article is only shown with its excerpt
 * @param seedTaxonomy create the curated tags at startup (idempotent)
 */
@ConfigurationProperties("intellhub.enrichment")
@Validated
record EnrichmentProperties(
		String provider,
		@DefaultValue("250") @Min(1) @Max(100_000) int dailyBudget,
		@DefaultValue("50") @Min(0) int reservedCalls,
		@DefaultValue("5") @Min(1) @Max(20) int batchSize,
		@DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
		@DefaultValue("30s") @NotNull Duration pollInterval,
		@DefaultValue("10m") @NotNull Duration staleAfter,
		@DefaultValue("20") @Min(1) @Max(200) int maxBatchesPerTick,
		@DefaultValue("UTC") @NotNull ZoneId budgetZone,
		@DefaultValue("5000") @Min(0) int maxFreeTags,
		@DefaultValue("0.15") @DecimalMin("0.0") @DecimalMax("1.0") double triageThreshold,
		@DefaultValue("true") boolean seedTaxonomy) {

	private static final Set<String> KNOWN = Set.of("gemini", "groq");

	boolean enabled() {
		return provider != null && !provider.isBlank();
	}

	@AssertTrue(message = "reserved-calls must not exceed daily-budget")
	boolean isReserveWithinBudget() {
		return reservedCalls <= dailyBudget;
	}

	/** The configured providers in order of use; empty when off. */
	List<String> providers() {
		return enabled() ? List.of(provider.split(",", -1)) : List.of();
	}

	/**
	 * Exact names on purpose: a stray space or a typo would otherwise enable the scheduler but no provider,
	 * silently. The fake provider cannot be mixed with real ones, because it would hide real failures.
	 */
	@AssertTrue(message = "provider must be unset or a comma-separated list (no spaces, no repeats) of: gemini, groq; or exactly: fake")
	boolean isProviderKnown() {
		if (!enabled()) {
			return true;
		}
		List<String> names = providers();
		if (new HashSet<>(names).size() != names.size()) {
			return false;
		}
		if (names.contains("fake")) {
			return names.size() == 1;
		}
		return KNOWN.containsAll(names);
	}

}
