package com.intellhub.identity.internal;

import com.intellhub.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Cross-site request protection for the endpoints that act on the refresh cookie.
 *
 * <p>Defence in depth on top of the {@code SameSite=Strict} cookie: a custom header that a
 * cross-site form or image cannot send, and an {@code Origin} check when the browser provides one.
 */
@Component
class CookieGuard {

	static final String HEADER = "X-Requested-By";

	static final String HEADER_VALUE = "intellhub";

	private final IdentityProperties properties;

	CookieGuard(IdentityProperties properties) {
		this.properties = properties;
	}

	void check(HttpServletRequest request) {
		if (!HEADER_VALUE.equals(request.getHeader(HEADER))) {
			throw denied();
		}
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (origin != null && !properties.allowedOrigins().contains(origin)) {
			throw denied();
		}
	}

	private static ApiException denied() {
		return new ApiException(HttpStatus.FORBIDDEN, "CSRF_CHECK_FAILED", "Request origin not allowed");
	}

}
