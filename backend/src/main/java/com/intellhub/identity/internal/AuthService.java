package com.intellhub.identity.internal;

import com.intellhub.identity.Role;
import com.intellhub.identity.internal.JwtService.AccessToken;
import com.intellhub.identity.internal.RefreshTokenService.IssuedRefreshToken;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh and logout. Failures are empty results with no detail, so callers cannot tell an
 * unknown email from a wrong password.
 */
@Service
class AuthService {

	private final UserRepository users;

	private final PasswordService passwords;

	private final JwtService jwt;

	private final RefreshTokenService refreshTokens;

	private final LoginRateLimiter limiter;

	AuthService(UserRepository users, PasswordService passwords, JwtService jwt, RefreshTokenService refreshTokens,
			LoginRateLimiter limiter) {
		this.users = users;
		this.passwords = passwords;
		this.jwt = jwt;
		this.refreshTokens = refreshTokens;
		this.limiter = limiter;
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * @throws TooManyAttemptsException when the email or client address has failed too often
	 * @return the tokens, or empty if the credentials are wrong or the account is disabled
	 *
	 * <p>Deliberately not transactional: BCrypt is slow, and a database connection must not be held
	 * while it runs. The repository calls and the token issue each run in their own short transaction.
	 */
	Optional<AuthResult> login(String email, String password, String clientAddress) {
		String emailKey = "email:" + normalizeEmail(email);
		String addressKey = "ip:" + clientAddress;
		Optional<Duration> blocked = limiter.blockedFor(emailKey).or(() -> limiter.blockedFor(addressKey));
		if (blocked.isPresent()) {
			throw new TooManyAttemptsException(blocked.get());
		}
		// Reserve the attempt now; it is refunded below if the login succeeds.
		limiter.recordAttempt(emailKey);
		limiter.recordAttempt(addressKey);
		Optional<User> found = users.findByEmail(normalizeEmail(email));
		if (found.isEmpty()) {
			passwords.burn(password);
			return Optional.empty();
		}
		User user = found.get();
		boolean passwordOk = passwords.matches(password, user.getPasswordHash());
		if (!passwordOk || !user.isEnabled()) {
			return Optional.empty();
		}
		limiter.reset(emailKey);
		limiter.refund(addressKey);
		return Optional.of(result(user.getId(), user.getRole(), refreshTokens.issueForLogin(user.getId())));
	}

	/** Exchanges a refresh token for a new access token and a rotated refresh token. */
	@Transactional
	Optional<AuthResult> refresh(String rawRefreshToken) {
		Optional<RefreshTokenService.Rotation> rotation = refreshTokens.rotate(rawRefreshToken);
		if (rotation.isEmpty()) {
			return Optional.empty();
		}
		RefreshTokenService.Rotation next = rotation.get();
		Optional<User> user = users.findById(next.userId()).filter(User::isEnabled);
		if (user.isEmpty()) {
			refreshTokens.revokeFamily(next.familyId());
			return Optional.empty();
		}
		return Optional.of(result(user.get().getId(), user.get().getRole(), next.token()));
	}

	@Transactional
	void logout(String rawRefreshToken) {
		refreshTokens.revokeFamilyOf(rawRefreshToken);
	}

	private AuthResult result(UUID userId, Role role, IssuedRefreshToken refresh) {
		return new AuthResult(jwt.issue(userId, role), refresh, userId, role);
	}

	record AuthResult(AccessToken accessToken, IssuedRefreshToken refreshToken, UUID userId, Role role) {
	}

}
