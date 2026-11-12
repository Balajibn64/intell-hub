package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

	private final LoginRateLimiter limiter = new LoginRateLimiter(TestProperties.defaults(), clock);

	@Test
	void notBlockedBelowTheLimit() {
		for (int i = 0; i < 4; i++) {
			limiter.recordAttempt("email:a");
		}

		assertThat(limiter.blockedFor("email:a")).isEmpty();
	}

	@Test
	void blockedAtTheLimitWithTimeUntilTheOldestFailureExpires() {
		for (int i = 0; i < 5; i++) {
			limiter.recordAttempt("email:a");
			clock.advance(Duration.ofMinutes(1));
		}

		assertThat(limiter.blockedFor("email:a")).hasValue(Duration.ofMinutes(10));
	}

	@Test
	void unblocksAfterTheWindowPasses() {
		for (int i = 0; i < 5; i++) {
			limiter.recordAttempt("email:a");
		}
		clock.advance(Duration.ofMinutes(16));

		assertThat(limiter.blockedFor("email:a")).isEmpty();
	}

	@Test
	void keysAreIndependent() {
		for (int i = 0; i < 5; i++) {
			limiter.recordAttempt("email:a");
		}

		assertThat(limiter.blockedFor("email:a")).isPresent();
		assertThat(limiter.blockedFor("email:b")).isEmpty();
	}

	@Test
	void refundGivesBackTheMostRecentAttempt() {
		for (int i = 0; i < 5; i++) {
			limiter.recordAttempt("ip:1");
		}
		assertThat(limiter.blockedFor("ip:1")).isPresent();

		limiter.refund("ip:1");

		assertThat(limiter.blockedFor("ip:1")).isEmpty();
		limiter.refund("ip:never-seen");
	}

	@Test
	void resetClearsTheFailures() {
		for (int i = 0; i < 5; i++) {
			limiter.recordAttempt("email:a");
		}
		limiter.reset("email:a");

		assertThat(limiter.blockedFor("email:a")).isEmpty();
	}

}
