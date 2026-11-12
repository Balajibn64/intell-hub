package com.intellhub.identity.internal;

import com.intellhub.shared.Placeholders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the identity module. Secrets come from environment variables, never the repo.
 *
 * @param jwt access token settings
 * @param refresh refresh token and cookie settings
 * @param admin optional first administrator, created only when no users exist
 * @param allowedOrigins browser origins allowed to call the cookie endpoints
 * @param loginLimit login failure throttling
 * @param bcryptStrength BCrypt cost factor (lower only in tests)
 */
@ConfigurationProperties("intellhub.identity")
@Validated
record IdentityProperties(
		@Valid @NotNull @DefaultValue Jwt jwt,
		@Valid @NotNull @DefaultValue Refresh refresh,
		@NotNull @DefaultValue Admin admin,
		@DefaultValue("http://localhost:5173") List<String> allowedOrigins,
		@Valid @NotNull @DefaultValue LoginLimit loginLimit,
		@DefaultValue("12") @Min(4) @Max(16) int bcryptStrength) {

	/** The HS256 signing secret must be at least 32 characters (so at least 32 bytes). */
	record Jwt(
			@NotBlank @Size(min = 32, message = "JWT secret must be at least 32 characters") String secret,
			@DefaultValue("intellhub") @NotBlank String issuer,
			@DefaultValue("15m") @NotNull Duration accessTtl) {

		@AssertTrue(message = "JWT secret still has the placeholder value from .env.example; generate a real one")
		boolean isNotPlaceholder() {
			return !Placeholders.isPlaceholder(secret);
		}

	}

	record Refresh(
			@DefaultValue("14d") @NotNull Duration ttl,
			@DefaultValue("false") boolean cookieSecure) {
	}

	record Admin(String email, String password) {

		boolean configured() {
			return email != null && !email.isBlank() && password != null && !password.isBlank();
		}

	}

	record LoginLimit(
			@DefaultValue("5") @Min(1) int maxFailures,
			@DefaultValue("15m") @NotNull Duration window) {
	}

}
