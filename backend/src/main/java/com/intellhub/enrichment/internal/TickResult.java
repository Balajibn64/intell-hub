package com.intellhub.enrichment.internal;

/**
 * What one worker run did. Counts only: nothing here comes from an article or a model.
 *
 * @param stoppedBecause why the run ended early (budget spent, provider failed, backoff, not enabled), or null
 */
record TickResult(boolean ran, int recovered, int triaged, int batches, int enriched, int retried, int failed,
		String stoppedBecause) {

	static TickResult notRun(String reason) {
		return new TickResult(false, 0, 0, 0, 0, 0, 0, reason);
	}

}
