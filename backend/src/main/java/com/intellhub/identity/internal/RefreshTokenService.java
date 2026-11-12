package com.intellhub.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens. Only a SHA-256 of each token is stored.
 *
 * <p>Rejections are returned as empty results, not thrown, so that revoking a replayed family is
 * committed instead of rolled back with the failed request.
 */
@Service
class RefreshTokenService {

	/** Tokens longer than this cannot be ours; skip hashing and lookup. */
	private static final int MAX_TOKEN_LENGTH = 200;

	private final RefreshTokenRepository tokens;

	private final IdentityProperties properties;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	RefreshTokenService(RefreshTokenRepository tokens, IdentityProperties properties, Clock clock) {
		this.tokens = tokens;
		this.properties = properties;
		this.clock = clock;
	}

	/** Starts a new family for a fresh login. */
	@Transactional
	IssuedRefreshToken issueForLogin(UUID userId) {
		return issue(userId, UUID.randomUUID()).token();
	}

	/**
	 * Exchanges a token for its successor. Empty means rejected: unknown, expired, or already used.
	 * Using a token that was already rotated is treated as theft and revokes the whole family.
	 */
	@Transactional
	Optional<Rotation> rotate(String rawToken) {
		if (!plausible(rawToken)) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		Optional<RefreshToken> found = tokens.findByTokenHashForUpdate(hash(rawToken));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		RefreshToken current = found.get();
		if (current.isRevoked()) {
			tokens.revokeFamily(current.getFamilyId(), now);
			return Optional.empty();
		}
		if (current.isExpired(now)) {
			return Optional.empty();
		}
		Rotation next = issue(current.getUserId(), current.getFamilyId());
		current.rotate(next.tokenId(), now);
		tokens.save(current);
		return Optional.of(next);
	}

	/** Revokes the whole family the given token belongs to. Unknown tokens are ignored. */
	@Transactional
	void revokeFamilyOf(String rawToken) {
		if (!plausible(rawToken)) {
			return;
		}
		tokens.findByTokenHash(hash(rawToken))
				.ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
	}

	@Transactional
	void revokeFamily(UUID familyId) {
		tokens.revokeFamily(familyId, clock.instant());
	}

	private Rotation issue(UUID userId, UUID familyId) {
		Instant now = clock.instant();
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant expiresAt = now.plus(properties.refresh().ttl());
		RefreshToken stored = new RefreshToken(UUID.randomUUID(), userId, familyId, hash(raw), expiresAt, now);
		tokens.save(stored);
		return new Rotation(userId, familyId, stored.getId(), new IssuedRefreshToken(raw, expiresAt));
	}

	private static boolean plausible(String rawToken) {
		return rawToken != null && !rawToken.isEmpty() && rawToken.length() <= MAX_TOKEN_LENGTH;
	}

	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	/** A token value (shown once, to the client) and when it stops being valid. */
	record IssuedRefreshToken(String value, Instant expiresAt) {
	}

	record Rotation(UUID userId, UUID familyId, UUID tokenId, IssuedRefreshToken token) {
	}

}
