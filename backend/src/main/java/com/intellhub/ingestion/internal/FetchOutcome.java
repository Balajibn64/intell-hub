package com.intellhub.ingestion.internal;

/** The result of fetching a URL: the body, or a failure with a fixed reason. */
sealed interface FetchOutcome {

	/** The body is the decoded response, never more than the configured limit. */
	record Success(byte[] body, String finalUrl, String contentType) implements FetchOutcome {
	}

	record Failure(Reason reason, int httpStatus) implements FetchOutcome {

		/**
		 * A short fixed description that is safe to store and show. It never contains response bodies,
		 * URLs or raw exception text.
		 */
		public String message() {
			return switch (reason) {
				case TIMEOUT -> "Timed out";
				case HTTP_ERROR -> "HTTP " + httpStatus;
				case TOO_LARGE -> "Response too large";
				case BLOCKED_ADDRESS -> "Blocked address";
				case TOO_MANY_REDIRECTS -> "Too many redirects";
				case INVALID_URL -> "Invalid URL";
				case NETWORK_ERROR -> "Network error";
			};
		}

	}

	enum Reason {
		TIMEOUT, HTTP_ERROR, TOO_LARGE, BLOCKED_ADDRESS, TOO_MANY_REDIRECTS, INVALID_URL, NETWORK_ERROR
	}

}
