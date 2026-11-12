package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.identity.TokenMaintenance;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TokenMaintenanceTests {

	private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

	private final TokenMaintenance maintenance;

	private final JdbcTemplate jdbc;

	private UUID user;

	TokenMaintenanceTests(TokenMaintenance maintenance, JdbcTemplate jdbc) {
		this.maintenance = maintenance;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from users");
		user = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'USER', true, now())", user,
				"t-" + user + "@example.com");
	}

	private UUID token(Instant expiresAt, Instant revokedAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into refresh_tokens (id, user_id, family_id, token_hash, expires_at, revoked_at, created_at)
				values (?, ?, ?, ?, ?, ?, now())""", id, user, UUID.randomUUID(), id.toString().replace("-", "") + id.toString().replace("-", ""),
				Timestamp.from(expiresAt), revokedAt == null ? null : Timestamp.from(revokedAt));
		return id;
	}

	private boolean exists(UUID id) {
		return jdbc.queryForObject("select count(*) from refresh_tokens where id = ?", Integer.class, id) == 1;
	}

	@Test
	void expiredTokensAreDeletedWhateverTheirStateAndTheRestAreKept() {
		UUID expired = token(NOW.minus(Duration.ofDays(2)), null);
		UUID expiredAndRevoked = token(NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(5)));
		UUID active = token(NOW.plus(Duration.ofDays(5)), null);
		UUID revokedButNotExpired = token(NOW.plus(Duration.ofDays(5)), NOW.minus(Duration.ofHours(1)));

		int deleted = maintenance.purgeExpiredRefreshTokens(NOW.minus(Duration.ofDays(1)));

		assertThat(deleted).isEqualTo(2);
		assertThat(exists(expired)).isFalse();
		assertThat(exists(expiredAndRevoked)).isFalse();
		assertThat(exists(active)).isTrue();
		assertThat(exists(revokedButNotExpired)).isTrue(); // replaying it is how token theft is noticed
	}

	@Test
	void theCutoffIsExclusiveAndATokenNotYetExpiredIsNeverTouched() {
		UUID atCutoff = token(NOW, null);
		UUID justBefore = token(NOW.minusSeconds(1), null);
		UUID justAfter = token(NOW.plusSeconds(1), null);

		assertThat(maintenance.purgeExpiredRefreshTokens(NOW)).isEqualTo(1);

		assertThat(exists(justBefore)).isFalse();
		assertThat(exists(atCutoff)).isTrue();
		assertThat(exists(justAfter)).isTrue();
	}

	@Test
	void nothingToDeleteIsZeroAndAMissingCutoffIsRefused() {
		token(NOW.plus(Duration.ofDays(1)), null);

		assertThat(maintenance.purgeExpiredRefreshTokens(NOW)).isZero();
		assertThatThrownBy(() -> maintenance.purgeExpiredRefreshTokens(null)).isInstanceOf(IllegalArgumentException.class);
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
	}

}
