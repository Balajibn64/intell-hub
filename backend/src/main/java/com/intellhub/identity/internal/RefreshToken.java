package com.intellhub.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A stored refresh token. Only the SHA-256 of the token value is kept. */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "replaced_by")
	private UUID replacedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected RefreshToken() {
		// for JPA
	}

	RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant expiresAt, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdAt = createdAt;
	}

	UUID getId() {
		return id;
	}

	UUID getUserId() {
		return userId;
	}

	UUID getFamilyId() {
		return familyId;
	}

	String getTokenHash() {
		return tokenHash;
	}

	Instant getExpiresAt() {
		return expiresAt;
	}

	Instant getRevokedAt() {
		return revokedAt;
	}

	UUID getReplacedBy() {
		return replacedBy;
	}

	boolean isRevoked() {
		return revokedAt != null;
	}

	boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}

	/** Marks this token as used by a rotation and points at its successor. */
	void rotate(UUID successorId, Instant now) {
		this.revokedAt = now;
		this.replacedBy = successorId;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof RefreshToken other && id != null && Objects.equals(id, other.id));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

}
