package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.identity.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class IdentityPersistenceTests {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private final UserRepository users;

	private final RefreshTokenRepository tokens;

	private final JdbcTemplate jdbc;

	IdentityPersistenceTests(UserRepository users, RefreshTokenRepository tokens, JdbcTemplate jdbc) {
		this.users = users;
		this.tokens = tokens;
		this.jdbc = jdbc;
	}

	@Test
	void savesAndFindsUserByEmail() {
		User saved = users.saveAndFlush(user("alice@example.com", Role.USER));

		assertThat(users.findByEmail("alice@example.com")).contains(saved);
		assertThat(users.existsByEmail("alice@example.com")).isTrue();
		assertThat(users.existsByEmail("nobody@example.com")).isFalse();
		assertThat(saved.isEnabled()).isTrue();
	}

	@Test
	void databaseRejectsDuplicateEmail() {
		users.saveAndFlush(user("dup@example.com", Role.USER));
		User duplicate = user("dup@example.com", Role.ADMIN);

		assertThatThrownBy(() -> users.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsUppercaseEmail() {
		User mixedCase = user("Upper@Example.com", Role.USER);

		assertThatThrownBy(() -> users.saveAndFlush(mixedCase))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsUnknownRole() {
		UUID id = UUID.randomUUID();
		String sql = "insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'ROOT', true, now())";

		assertThatThrownBy(() -> jdbc.update(sql, id, "root@example.com"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void revokeFamilyRevokesOnlyActiveTokensOfThatFamily() {
		User owner = users.saveAndFlush(user("owner@example.com", Role.USER));
		UUID family = UUID.randomUUID();
		RefreshToken first = tokens.save(token(owner, family, "hash-1"));
		RefreshToken second = tokens.save(token(owner, family, "hash-2"));
		tokens.save(token(owner, UUID.randomUUID(), "hash-3"));
		first.rotate(second.getId(), NOW.minusSeconds(60));
		tokens.flush();

		int revoked = tokens.revokeFamily(family, NOW);

		assertThat(revoked).isEqualTo(1);
		assertThat(tokens.findByTokenHash("hash-1").orElseThrow().getRevokedAt()).isEqualTo(NOW.minusSeconds(60));
		assertThat(tokens.findByTokenHash("hash-2").orElseThrow().isRevoked()).isTrue();
		assertThat(tokens.findByTokenHash("hash-3").orElseThrow().isRevoked()).isFalse();
	}

	@Test
	void deletingUserDeletesTheirTokens() {
		User owner = users.saveAndFlush(user("gone@example.com", Role.USER));
		tokens.saveAndFlush(token(owner, UUID.randomUUID(), "hash-gone"));

		users.delete(owner);
		users.flush();

		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = 'hash-gone'",
				Integer.class)).isZero();
	}

	private static User user(String email, Role role) {
		return new User(UUID.randomUUID(), email, "{bcrypt}placeholder", role, NOW);
	}

	private static RefreshToken token(User owner, UUID family, String hash) {
		return new RefreshToken(UUID.randomUUID(), owner.getId(), family, hash, NOW.plusSeconds(3600), NOW);
	}

}
