package com.intellhub.ingestion.internal;

import java.time.Duration;

/**
 * Limits for one fetch of one source.
 *
 * @param connectTimeout time allowed to open a connection
 * @param responseTimeout time allowed to wait for data on an open connection
 * @param totalTimeout hard limit for the whole fetch including redirects, which also stops a slow trickle
 * @param maxBodyBytes largest decoded body accepted
 * @param maxRedirects most redirects followed
 * @param userAgent value sent as User-Agent
 */
record FetchSettings(Duration connectTimeout, Duration responseTimeout, Duration totalTimeout, long maxBodyBytes,
		int maxRedirects, String userAgent) {

	static FetchSettings defaults() {
		return new FetchSettings(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20), 2L * 1024 * 1024,
				3, "IntellHub/0.1 (+feed reader)");
	}

}
