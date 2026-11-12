package com.intellhub.identity.internal;

import com.intellhub.shared.ApiException;
import java.time.Duration;
import org.springframework.http.HttpStatus;

/** Too many failed logins. Carries how long the caller should wait. */
class TooManyAttemptsException extends ApiException {

	private final Duration retryAfter;

	TooManyAttemptsException(Duration retryAfter) {
		super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many failed attempts. Try again later.");
		this.retryAfter = retryAfter;
	}

	Duration getRetryAfter() {
		return retryAfter;
	}

}
