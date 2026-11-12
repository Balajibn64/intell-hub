package com.intellhub.articles;

/** Which waiting articles a worker wants to claim. Readers' requests and background work are kept apart. */
public enum ClaimKind {

	/** Articles a reader asked for. These may use the part of the daily budget held back for readers. */
	REQUESTED,

	/** Everything else. This may not touch the reserved part of the budget. */
	BACKGROUND

}
