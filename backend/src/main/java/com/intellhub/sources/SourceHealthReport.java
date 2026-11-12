package com.intellhub.sources;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How the sources are doing, for the admin status page.
 *
 * @param total all sources
 * @param enabled how many are enabled
 * @param failing enabled sources whose last fetch failed
 * @param neverFetched enabled sources that have not been fetched yet
 * @param sources every source with its health, oldest first
 */
public record SourceHealthReport(int total, int enabled, int failing, int neverFetched, List<SourceHealth> sources) {

	/**
	 * One source's health. {@code lastError} is one of the fixed short texts ingestion stores, never text from the
	 * source itself.
	 */
	public record SourceHealth(UUID id, String name, SourceType type, boolean enabled, Instant lastFetchedAt, String lastStatus,
			String lastError, int consecutiveFailures) {
	}

}
