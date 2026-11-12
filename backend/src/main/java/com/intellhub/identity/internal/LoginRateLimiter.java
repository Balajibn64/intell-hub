package com.intellhub.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter for login attempts, keyed by email and by client address.
 *
 * <p>An attempt is recorded <em>before</em> the slow password check and refunded on success, so a burst
 * of parallel guesses cannot all slip past the limit while the first checks are still running.
 *
 * <p>In memory and per instance: it resets on restart and does not span several servers. That is
 * acceptable for a small single-instance deployment. Keys are capped so a flood of random emails
 * cannot grow memory without bound.
 */
@Component
class LoginRateLimiter {

	private static final int MAX_KEYS = 10_000;

	private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

	private final IdentityProperties.LoginLimit limit;

	private final Clock clock;

	LoginRateLimiter(IdentityProperties properties, Clock clock) {
		this.limit = properties.loginLimit();
		this.clock = clock;
	}

	/** How long until this key may try again, or empty if it is not blocked. */
	Optional<Duration> blockedFor(String key) {
		Deque<Instant> window = failures.get(key);
		if (window == null) {
			return Optional.empty();
		}
		synchronized (window) {
			prune(window);
			if (window.size() < limit.maxFailures()) {
				return Optional.empty();
			}
			Instant unblockAt = window.peekFirst().plus(limit.window());
			Duration wait = Duration.between(clock.instant(), unblockAt);
			return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
		}
	}

	/** Counts an attempt against this key. */
	void recordAttempt(String key) {
		if (failures.size() >= MAX_KEYS) {
			evictExpired();
		}
		Deque<Instant> window = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (window) {
			prune(window);
			window.addLast(clock.instant());
		}
	}

	void reset(String key) {
		failures.remove(key);
	}

	/** Gives back the most recent attempt, for a login that turned out to be legitimate. */
	void refund(String key) {
		Deque<Instant> window = failures.get(key);
		if (window != null) {
			synchronized (window) {
				window.pollLast();
			}
		}
	}

	private void prune(Deque<Instant> window) {
		Instant cutoff = clock.instant().minus(limit.window());
		while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
			window.removeFirst();
		}
	}

	private void evictExpired() {
		failures.entrySet().removeIf(entry -> {
			synchronized (entry.getValue()) {
				prune(entry.getValue());
				return entry.getValue().isEmpty();
			}
		});
		if (failures.size() >= MAX_KEYS) {
			failures.clear();
		}
	}

}
