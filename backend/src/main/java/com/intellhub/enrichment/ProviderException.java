package com.intellhub.enrichment;

import java.time.Duration;

/** A provider could not complete a batch. The message must never contain article text or model output. */
public class ProviderException extends Exception {

	public enum Kind {
		RATE_LIMITED, TIMEOUT, UNAVAILABLE, BAD_RESPONSE
	}

	private final Kind kind;

	private final Duration retryAfter;

	public ProviderException(Kind kind, String message) {
		this(kind, message, null);
	}

	public ProviderException(Kind kind, String message, Duration retryAfter) {
		super(message, null, false, false);
		this.kind = kind;
		this.retryAfter = retryAfter;
	}

	public Kind getKind() {
		return kind;
	}

	/** How long the provider asked us to wait, or null. */
	public Duration getRetryAfter() {
		return retryAfter;
	}

}
