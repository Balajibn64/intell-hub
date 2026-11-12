package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.identity.Role;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminBootstrapTests {

	private static final String PASSWORD = "bootstrap-password-123";

	private final UserRepository users;

	private final RefreshTokenRepository tokens;

	private final UserService userService;

	private final AuthService auth;

	private final PasswordService passwords;

	AdminBootstrapTests(UserRepository users, RefreshTokenRepository tokens, UserService userService, AuthService auth,
			PasswordService passwords) {
		this.users = users;
		this.tokens = tokens;
		this.userService = userService;
		this.auth = auth;
		this.passwords = passwords;
	}

	@BeforeEach
	void setUp() {
		tokens.deleteAll();
		users.deleteAll();
	}

	private AdminBootstrap bootstrapWith(String email, String password) {
		IdentityProperties base = TestProperties.defaults();
		IdentityProperties props = new IdentityProperties(base.jwt(), base.refresh(),
				new IdentityProperties.Admin(email, password), base.allowedOrigins(), base.loginLimit(),
				base.bcryptStrength());
		return new AdminBootstrap(users, userService, props);
	}

	@Test
	void createsTheFirstAdminWhenThereAreNoUsers() {
		bootstrapWith("First.Admin@Example.com", PASSWORD).run(null);

		User admin = users.findByEmail("first.admin@example.com").orElseThrow();
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.isEnabled()).isTrue();
		assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
		assertThat(auth.login("first.admin@example.com", PASSWORD, "10.3.0.1")).isPresent();
	}

	@Test
	void runningTwiceDoesNotCreateASecondAccount() {
		bootstrapWith("first.admin@example.com", PASSWORD).run(null);
		bootstrapWith("first.admin@example.com", PASSWORD).run(null);
		bootstrapWith("another@example.com", PASSWORD).run(null);

		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void doesNothingWhenUsersAlreadyExist() {
		users.save(new User(UUID.randomUUID(), "existing@example.com", passwords.hash(PASSWORD), Role.USER,
				Instant.now()));

		bootstrapWith("first.admin@example.com", PASSWORD).run(null);

		assertThat(users.findByEmail("first.admin@example.com")).isEmpty();
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void doesNothingWhenNotConfigured() {
		bootstrapWith(null, null).run(null);
		bootstrapWith("admin@example.com", "").run(null);
		bootstrapWith("", PASSWORD).run(null);

		assertThat(users.count()).isZero();
	}

	@Test
	void thePlaceholderPasswordFromTheExampleFileFailsStartup() {
		assertThatThrownBy(() -> bootstrapWith("admin@example.com", "change-me-to-a-strong-password").run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("placeholder")
				.hasMessageNotContaining("change-me-to-a-strong-password");

		assertThat(users.count()).isZero();
	}

	@Test
	void aWeakConfiguredPasswordFailsStartupWithoutRevealingIt() {
		assertThatThrownBy(() -> bootstrapWith("admin@example.com", "short").run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ADMIN_PASSWORD")
				.hasMessageNotContaining("short\"");

		assertThat(users.count()).isZero();
	}

}
