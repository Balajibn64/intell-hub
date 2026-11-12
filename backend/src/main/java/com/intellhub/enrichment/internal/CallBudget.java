package com.intellhub.enrichment.internal;

/** The daily limit on provider calls. Every real provider call spends quota, so every attempt must ask first. */
interface CallBudget {

	/** Takes one call from today's budget. False means the budget for this priority is spent. */
	boolean tryReserve(BudgetPriority priority, String provider);

	/** How many calls are still available today for the given priority. */
	int remaining(BudgetPriority priority);

	/** Notes that a call that was made failed. It was already counted when reserved. */
	void recordFailure(String provider);

}
