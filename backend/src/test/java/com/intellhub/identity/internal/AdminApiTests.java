package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.identity.Role;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
class AdminApiTests {

	private static final String PASSWORD = "correct-horse-battery";

	private final MockMvc mvc;

	private final UserRepository users;

	private final RefreshTokenRepository tokens;

	private final PasswordService passwords;

	private RequestPostProcessor fromAddress;

	AdminApiTests(MockMvc mvc, UserRepository users, RefreshTokenRepository tokens, PasswordService passwords) {
		this.mvc = mvc;
		this.users = users;
		this.tokens = tokens;
		this.passwords = passwords;
	}

	@BeforeEach
	void setUp() {
		tokens.deleteAll();
		users.deleteAll();
		String address = "10.2." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
		fromAddress = request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	/** Creates a user directly and returns a bearer header for them. */
	private String bearerFor(String email, Role role) throws Exception {
		users.save(new User(UUID.randomUUID(), email, passwords.hash(PASSWORD), role, Instant.now()));
		MvcResult login = mvc.perform(post("/api/v1/auth/login").with(fromAddress).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
		String token = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
		return "Bearer " + token;
	}

	private static String newUserJson(String email, String password, String role) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":" + role + "}";
	}

	@Test
	void anonymousCallersGet401() throws Exception {
		mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
				.content(newUserJson("a@example.com", PASSWORD, "\"USER\""))).andExpect(status().isUnauthorized());
	}

	@Test
	void aPlainUserGets403OnEveryAdminEndpointAndCreatesNothing() throws Exception {
		String bearer = bearerFor("plain@example.com", Role.USER);

		mvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(post("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content(newUserJson("sneaky@example.com", PASSWORD, "\"ADMIN\"")))
				.andExpect(status().isForbidden());

		assertThat(users.existsByEmail("sneaky@example.com")).isFalse();
	}

	@Test
	void anAdminCanListAndCreateUsersAndTheNewUserCanLogIn() throws Exception {
		String bearer = bearerFor("boss@example.com", Role.ADMIN);

		mvc.perform(post("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content(newUserJson("New.Person@Example.com", "a-long-enough-password", "\"USER\"")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("new.person@example.com"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());

		mvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].email").value("boss@example.com"))
				.andExpect(jsonPath("$[1].email").value("new.person@example.com"))
				.andExpect(jsonPath("$[0].passwordHash").doesNotExist());

		mvc.perform(post("/api/v1/auth/login").with(fromAddress).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"new.person@example.com\",\"password\":\"a-long-enough-password\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void duplicateEmailsAreRejectedIgnoringCase() throws Exception {
		String bearer = bearerFor("boss@example.com", Role.ADMIN);

		mvc.perform(post("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content(newUserJson("BOSS@example.com", "a-long-enough-password", "\"USER\"")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
	}

	@Test
	void invalidInputIsRejectedWithFieldDetails() throws Exception {
		String bearer = bearerFor("boss@example.com", Role.ADMIN);

		mvc.perform(post("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON).content(newUserJson("not-an-email", "short", "null")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.email").exists())
				.andExpect(jsonPath("$.details.password").exists())
				.andExpect(jsonPath("$.details.role").exists());
		mvc.perform(post("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content(newUserJson("ok@example.com", "a-long-enough-password", "\"ROOT\"")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aPasswordOverBcryptsByteLimitIsABadRequestNotAServerError() throws Exception {
		String bearer = bearerFor("boss@example.com", Role.ADMIN);
		String multiByte = "é".repeat(40); // 40 characters, 80 bytes

		mvc.perform(post("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON).content(newUserJson("wide@example.com", multiByte, "\"USER\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

}
