package com.intellhub.identity.internal;

import com.intellhub.identity.internal.AuthService.AuthResult;
import com.intellhub.shared.ApiError;
import com.intellhub.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	static final String REFRESH_COOKIE = "refresh_token";

	private static final String COOKIE_PATH = "/api/v1/auth";

	private final AuthService auth;

	private final CookieGuard cookieGuard;

	private final IdentityProperties properties;

	private final Clock clock;

	AuthController(AuthService auth, CookieGuard cookieGuard, IdentityProperties properties, Clock clock) {
		this.auth = auth;
		this.cookieGuard = cookieGuard;
		this.properties = properties;
		this.clock = clock;
	}

	@PostMapping("/login")
	ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
		// getRemoteAddr is used on purpose: X-Forwarded-For is client-controlled unless a trusted
		// proxy is configured, and there is no proxy yet.
		Optional<AuthResult> result = auth.login(body.email(), body.password(), request.getRemoteAddr());
		return result.map(this::tokens).orElseThrow(AuthController::invalidCredentials);
	}

	@PostMapping("/refresh")
	ResponseEntity<TokenResponse> refresh(
			@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletRequest request) {
		cookieGuard.check(request);
		Optional<AuthResult> result = refreshToken == null ? Optional.empty() : auth.refresh(refreshToken);
		return result.map(this::tokens).orElseThrow(() -> new InvalidRefreshTokenException());
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
			HttpServletRequest request) {
		cookieGuard.check(request);
		if (refreshToken != null) {
			auth.logout(refreshToken);
		}
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedCookie().toString()).build();
	}

	/** A rejected refresh also clears the cookie so the browser stops sending a dead token. */
	@ExceptionHandler(InvalidRefreshTokenException.class)
	ResponseEntity<ApiError> handleInvalidRefresh(InvalidRefreshTokenException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
				.body(ApiError.of("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired"));
	}

	@ExceptionHandler(TooManyAttemptsException.class)
	ResponseEntity<ApiError> handleTooManyAttempts(TooManyAttemptsException ex) {
		long seconds = Math.max(1, ex.getRetryAfter().toSeconds());
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
				.body(ApiError.of(ex.getCode(), ex.getMessage()));
	}

	private ResponseEntity<TokenResponse> tokens(AuthResult result) {
		Duration maxAge = Duration.between(clock.instant(), result.refreshToken().expiresAt());
		ResponseCookie cookie = baseCookie(result.refreshToken().value()).maxAge(maxAge).build();
		TokenResponse body = new TokenResponse(result.accessToken().value(), "Bearer",
				result.accessToken().ttl().toSeconds());
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
	}

	private ResponseCookie clearedCookie() {
		return baseCookie("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
				.httpOnly(true)
				.secure(properties.refresh().cookieSecure())
				.sameSite("Strict")
				.path(COOKIE_PATH);
	}

	private static ApiException invalidCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
	}

	record LoginRequest(
			@NotBlank @Size(max = 254) String email,
			@NotBlank @Size(max = 128) String password) {
	}

	record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	static class InvalidRefreshTokenException extends RuntimeException {

		InvalidRefreshTokenException() {
			super("Invalid refresh token", null, false, false);
		}

	}

}
