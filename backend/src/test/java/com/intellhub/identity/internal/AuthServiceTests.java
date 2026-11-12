package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.identity.Role;
import com.intellhub.identity.internal.AuthService.AuthResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuthServiceTests {

	private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

	private static final String PASSWORD = "correct-horse-battery";

	@TestConfiguration
	static class ClockOverride {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(START);
		}

	}

	private final AuthService auth;

	private final UserRepository users;

	private final RefreshTokenRepository tokens;

	private final PasswordService passwords;

	private final MutableClock clock;

	private final JdbcTemplate jdbc;

	// Unique per test so the in-memory login limiter never leaks between tests.
	private String email;

	private String address;

	AuthServiceTests(AuthService auth, UserRepository users, RefreshTokenRepository tokens, PasswordService passwords,
			MutableClock clock, JdbcTemplate jdbc) {
		this.auth = auth;
		this.users = users;
		this.tokens = tokens;
		this.passwords = passwords;
		this.clock = clock;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		tokens.deleteAll();
		users.deleteAll();
		clock.advance(Duration.between(clock.instant(), START));
		email = "user-" + UUID.randomUUID() + "@example.com";
		address = "10.0." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
	}

	private User createUser(Role role) {
		return users.save(new User(UUID.randomUUID(), email, passwords.hash(PASSWORD), role, START));
	}

	private AuthResult loginOrFail() {
		return auth.login(email, PASSWORD, address).orElseThrow();
	}

	@Test
	void loginIssuesTokensAndStoresOnlyAHashOfTheRefreshToken() {
		User user = createUser(Role.USER);

		AuthResult result = loginOrFail();

		assertThat(result.userId()).isEqualTo(user.getId());
		assertThat(result.role()).isEqualTo(Role.USER);
		assertThat(result.accessToken().value()).isNotBlank();
		String raw = result.refreshToken().value();
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, raw))
				.isZero();
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
				RefreshTokenService.hash(raw))).isEqualTo(1);
		assertThat(result.refreshToken().expiresAt()).isEqualTo(START.plus(Duration.ofDays(14)));
	}

	@Test
	void emailMatchingIgnoresCaseAndSurroundingSpaces() {
		createUser(Role.USER);

		assertThat(auth.login("  " + email.toUpperCase() + " ", PASSWORD, address)).isPresent();
	}

	@Test
	void wrongPasswordAndUnknownEmailBothFailTheSameWay() {
		createUser(Role.USER);

		assertThat(auth.login(email, "wrong-password-123", address)).isEmpty();
		assertThat(auth.login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD, address)).isEmpty();
	}

	@Test
	void disabledUserCannotLogIn() {
		User user = createUser(Role.USER);
		user.disable();
		users.save(user);

		assertThat(auth.login(email, PASSWORD, address)).isEmpty();
	}

	@Test
	void tooManyFailuresBlockEvenTheCorrectPasswordUntilTheWindowPasses() {
		createUser(Role.USER);
		for (int i = 0; i < 5; i++) {
			assertThat(auth.login(email, "wrong-password-123", address)).isEmpty();
		}

		assertThatThrownBy(() -> auth.login(email, PASSWORD, address)).isInstanceOf(TooManyAttemptsException.class);

		clock.advance(Duration.ofMinutes(16));
		assertThat(auth.login(email, PASSWORD, address)).isPresent();
	}

	@Test
	void successfulLoginsAreNeverThrottled() {
		createUser(Role.USER);

		for (int i = 0; i < 12; i++) {
			assertThat(auth.login(email, PASSWORD, address)).isPresent();
		}
	}

	@Test
	void aBurstOfParallelGuessesIsStoppedNearTheLimit() throws Exception {
		createUser(Role.USER);
		int threads = 40;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<Boolean> guess = () -> {
					ready.countDown();
					go.await();
					try {
						auth.login(email, "wrong-password-123", address);
						return true; // got as far as the password check
					}
					catch (TooManyAttemptsException blocked) {
						return false;
					}
				};
				futures.add(pool.submit(guess));
			}
			ready.await();
			go.countDown();
			int checked = 0;
			for (Future<Boolean> future : futures) {
				if (future.get()) {
					checked++;
				}
			}
			// The limit is 5. Allow slack for a few threads that race past the check, but far fewer than 40.
			assertThat(checked).isBetween(5, 15);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void refreshRotatesTheTokenAndTheOldOneIsThenAReplay() {
		createUser(Role.ADMIN);
		AuthResult first = loginOrFail();

		AuthResult second = auth.refresh(first.refreshToken().value()).orElseThrow();

		assertThat(second.refreshToken().value()).isNotEqualTo(first.refreshToken().value());
		assertThat(second.role()).isEqualTo(Role.ADMIN);
		// Replaying the old token is treated as theft: rejected, and the family dies with it.
		assertThat(auth.refresh(first.refreshToken().value())).isEmpty();
		assertThat(auth.refresh(second.refreshToken().value())).isEmpty();
	}

	@Test
	void unknownAndExpiredRefreshTokensAreRejected() {
		createUser(Role.USER);
		AuthResult login = loginOrFail();

		assertThat(auth.refresh("not-a-real-token")).isEmpty();
		assertThat(auth.refresh("x".repeat(500))).isEmpty();
		assertThat(auth.refresh(null)).isEmpty();

		clock.advance(Duration.ofDays(15));
		assertThat(auth.refresh(login.refreshToken().value())).isEmpty();
	}

	@Test
	void logoutRevokesTheWholeFamily() {
		createUser(Role.USER);
		AuthResult first = loginOrFail();
		AuthResult second = auth.refresh(first.refreshToken().value()).orElseThrow();

		auth.logout(second.refreshToken().value());

		assertThat(auth.refresh(second.refreshToken().value())).isEmpty();
		auth.logout("unknown-token");
		auth.logout(null);
	}

	@Test
	void refreshForADisabledUserIsRejectedAndRevokesTheFamily() {
		User user = createUser(Role.USER);
		AuthResult login = loginOrFail();
		user.disable();
		users.save(user);

		assertThat(auth.refresh(login.refreshToken().value())).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null", Integer.class))
				.isZero();
	}

	@Test
	void concurrentRefreshesWithOneTokenAllowExactlyOneSuccess() throws Exception {
		createUser(Role.USER);
		String token = loginOrFail().refreshToken().value();
		int threads = 4;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Optional<AuthResult>>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<Optional<AuthResult>> task = () -> {
					ready.countDown();
					go.await();
					return auth.refresh(token);
				};
				futures.add(pool.submit(task));
			}
			ready.await();
			go.countDown();
			int successes = 0;
			for (Future<Optional<AuthResult>> future : futures) {
				if (future.get().isPresent()) {
					successes++;
				}
			}
			assertThat(successes).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

}
