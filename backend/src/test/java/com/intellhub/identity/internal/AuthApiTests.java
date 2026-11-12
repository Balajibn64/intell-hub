package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.identity.Role;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuthApiTests {

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

	private final MockMvc mvc;

	private final UserRepository users;

	private final RefreshTokenRepository tokens;

	private final PasswordService passwords;

	private final MutableClock clock;

	// Unique per test so the in-memory login limiter never leaks between tests.
	private String email;

	private RequestPostProcessor fromAddress;

	AuthApiTests(MockMvc mvc, UserRepository users, RefreshTokenRepository tokens, PasswordService passwords,
			MutableClock clock) {
		this.mvc = mvc;
		this.users = users;
		this.tokens = tokens;
		this.passwords = passwords;
		this.clock = clock;
	}

	@BeforeEach
	void setUp() {
		tokens.deleteAll();
		users.deleteAll();
		clock.advance(Duration.between(clock.instant(), START));
		email = "user-" + UUID.randomUUID() + "@example.com";
		String address = "10.1." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
		fromAddress = request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	private User createUser(Role role) {
		return users.save(new User(UUID.randomUUID(), email, passwords.hash(PASSWORD), role, START));
	}

	private MvcResult login(String loginEmail, String password) throws Exception {
		String body = "{\"email\":\"" + loginEmail + "\",\"password\":\"" + password + "\"}";
		return mvc.perform(post("/api/v1/auth/login").with(fromAddress).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn();
	}

	private String accessToken(MvcResult login) throws Exception {
		return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
	}

	private String refreshCookie(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
		assertThat(cookie).isNotNull();
		return cookie.getValue();
	}

	private MvcResult refresh(String cookieValue) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(AuthController.REFRESH_COOKIE, cookieValue))
				.header(CookieGuard.HEADER, CookieGuard.HEADER_VALUE)).andReturn();
	}

	@Test
	void loginReturnsAnAccessTokenAndASafeRefreshCookie() throws Exception {
		createUser(Role.USER);

		MvcResult result = login(email, PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String body = result.getResponse().getContentAsString();
		assertThat((String) JsonPath.read(body, "$.accessToken")).isNotBlank();
		assertThat((String) JsonPath.read(body, "$.tokenType")).isEqualTo("Bearer");
		assertThat((Integer) JsonPath.read(body, "$.expiresIn")).isEqualTo(900);
		assertThat(body).doesNotContain("refresh");
		String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).contains(AuthController.REFRESH_COOKIE + "=")
				.contains("HttpOnly")
				.contains("SameSite=Strict")
				.contains("Path=/api/v1/auth")
				.contains("Max-Age=1209600")
				.doesNotContain("Secure");
	}

	@Test
	void wrongPasswordAndUnknownEmailGiveTheSameResponse() throws Exception {
		createUser(Role.USER);

		MvcResult wrongPassword = login(email, "wrong-password-123");
		MvcResult unknownEmail = login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD);

		assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
		assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
		assertThat(wrongPassword.getResponse().getContentAsString())
				.isEqualTo(unknownEmail.getResponse().getContentAsString())
				.contains("INVALID_CREDENTIALS");
		assertThat(wrongPassword.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
	}

	@Test
	void loginValidatesItsInput() throws Exception {
		mvc.perform(post("/api/v1/auth/login").with(fromAddress).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"\",\"password\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/auth/login").with(fromAddress).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"a@b.c\",\"password\":\"" + "x".repeat(129) + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aLongButAllowedWrongPasswordIsAnOrdinaryFailure() throws Exception {
		createUser(Role.USER);

		assertThat(login(email, "x".repeat(100)).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void repeatedFailuresAreThrottledWithRetryAfter() throws Exception {
		createUser(Role.USER);
		for (int i = 0; i < 5; i++) {
			assertThat(login(email, "wrong-password-123").getResponse().getStatus()).isEqualTo(401);
		}

		MvcResult blocked = login(email, PASSWORD);

		assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
		assertThat(blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
		assertThat(blocked.getResponse().getContentAsString()).contains("TOO_MANY_ATTEMPTS");
	}

	@Test
	void meNeedsAValidBearerToken() throws Exception {
		User user = createUser(Role.ADMIN);
		String token = accessToken(login(email, PASSWORD));

		mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(user.getId().toString()))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void anExpiredAccessTokenIsRejected() throws Exception {
		createUser(Role.USER);
		String token = accessToken(login(email, PASSWORD));

		clock.advance(Duration.ofMinutes(16));

		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void aValidTokenForADisabledUserIsRejected() throws Exception {
		User user = createUser(Role.USER);
		String token = accessToken(login(email, PASSWORD));
		user.disable();
		users.save(user);

		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void refreshRotatesTheCookieAndAReplayIsRejected() throws Exception {
		createUser(Role.USER);
		String first = refreshCookie(login(email, PASSWORD));

		MvcResult rotated = refresh(first);

		assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
		String second = refreshCookie(rotated);
		assertThat(second).isNotEqualTo(first);
		assertThat((String) JsonPath.read(rotated.getResponse().getContentAsString(), "$.accessToken")).isNotBlank();

		MvcResult replay = refresh(first);
		assertThat(replay.getResponse().getStatus()).isEqualTo(401);
		assertThat(replay.getResponse().getContentAsString()).contains("INVALID_REFRESH_TOKEN");
		assertThat(replay.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
		// The replay also killed the newer token from the same login.
		assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void refreshWithoutACookieIsUnauthorized() throws Exception {
		mvc.perform(post("/api/v1/auth/refresh").header(CookieGuard.HEADER, CookieGuard.HEADER_VALUE))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void refreshAndLogoutRequireTheCustomHeader() throws Exception {
		createUser(Role.USER);
		String cookie = refreshCookie(login(email, PASSWORD));

		mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(AuthController.REFRESH_COOKIE, cookie)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_CHECK_FAILED"));
		mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(AuthController.REFRESH_COOKIE, cookie)))
				.andExpect(status().isForbidden());
		// The rejected requests must not have used up the token.
		assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void refreshAndLogoutRejectAForeignOrigin() throws Exception {
		createUser(Role.USER);
		String cookie = refreshCookie(login(email, PASSWORD));

		mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(AuthController.REFRESH_COOKIE, cookie))
				.header(CookieGuard.HEADER, CookieGuard.HEADER_VALUE)
				.header(HttpHeaders.ORIGIN, "https://evil.example"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(AuthController.REFRESH_COOKIE, cookie))
				.header(CookieGuard.HEADER, CookieGuard.HEADER_VALUE)
				.header(HttpHeaders.ORIGIN, "https://evil.example"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(AuthController.REFRESH_COOKIE, cookie))
				.header(CookieGuard.HEADER, CookieGuard.HEADER_VALUE)
				.header(HttpHeaders.ORIGIN, "http://localhost:5173"))
				.andExpect(status().isOk());
	}

	@Test
	void logoutClearsTheCookieAndRevokesTheToken() throws Exception {
		createUser(Role.USER);
		String cookie = refreshCookie(login(email, PASSWORD));

		MvcResult logout = mvc.perform(post("/api/v1/auth/logout")
				.cookie(new Cookie(AuthController.REFRESH_COOKIE, cookie))
				.header(CookieGuard.HEADER, CookieGuard.HEADER_VALUE)).andReturn();

		assertThat(logout.getResponse().getStatus()).isEqualTo(204);
		assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
		assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void logoutWithoutACookieStillSucceeds() throws Exception {
		mvc.perform(post("/api/v1/auth/logout").header(CookieGuard.HEADER, CookieGuard.HEADER_VALUE))
				.andExpect(status().isNoContent());
	}

	@Test
	void onlyHealthIsPublicOutsideAuth() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/anything")).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/ingest")).andExpect(status().isUnauthorized());
	}

	@Test
	void unknownRoutesForALoggedInUserAreNotFoundWithTheSameErrorBody() throws Exception {
		createUser(Role.USER);
		String token = accessToken(login(email, PASSWORD));

		mvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

}
