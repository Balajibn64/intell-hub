package com.intellhub.ingestion.internal;

/** The bytes are not a usable feed. Deliberately carries no detail from the document. */
class FeedParseException extends Exception {

	FeedParseException() {
		super("Not a valid feed", null, false, false);
	}

}
