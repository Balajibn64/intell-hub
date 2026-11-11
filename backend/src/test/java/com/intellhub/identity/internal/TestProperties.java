package com.intellhub.identity.internal;

import java.time.Duration;
import java.util.List;

/** Builds {@link IdentityProperties} for unit tests, with a cheap BCrypt cost. */
final class TestProperties {

	static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";

	private TestProperties() {
	}

	static IdentityProperties with(String secret, String issuer) {
		return new IdentityProperties(
				new IdentityProperties.Jwt(secret, issuer, Duration.ofMinutes(15)),
				new IdentityProperties.Refresh(Duration.ofDays(14), false),
				new IdentityProperties.Admin(null, null),
				List.of("http://localhost:5173"),
				new IdentityProperties.LoginLimit(5, Duration.ofMinutes(15)),
				4);
	}

	static IdentityProperties defaults() {
		return with(SECRET, "intellhub");
	}

}
