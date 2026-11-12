package com.intellhub.enrichment.internal;

/** Who is asking for a call. A part of the daily budget is held back for readers' requests. */
enum BudgetPriority {

	/** Background batches: may use the budget except the reserved part. */
	NORMAL,

	/** An article a reader asked for: may use the whole budget, including the reserved part. */
	RESERVED

}
