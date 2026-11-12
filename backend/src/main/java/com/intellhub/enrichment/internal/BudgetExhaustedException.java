package com.intellhub.enrichment.internal;

/** Today's call budget is spent. Work stays queued until the next day. */
class BudgetExhaustedException extends Exception {

	BudgetExhaustedException() {
		super("Daily call budget is spent", null, false, false);
	}

}
