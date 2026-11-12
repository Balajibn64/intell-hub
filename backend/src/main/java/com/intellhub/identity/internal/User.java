package com.intellhub.identity.internal;

import com.intellhub.identity.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
class User {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected User() {
		// for JPA
	}

	User(UUID id, String email, String passwordHash, Role role, Instant createdAt) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = role;
		this.enabled = true;
		this.createdAt = createdAt;
	}

	UUID getId() {
		return id;
	}

	String getEmail() {
		return email;
	}

	String getPasswordHash() {
		return passwordHash;
	}

	Role getRole() {
		return role;
	}

	boolean isEnabled() {
		return enabled;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	void disable() {
		this.enabled = false;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof User other && id != null && Objects.equals(id, other.id));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

}
