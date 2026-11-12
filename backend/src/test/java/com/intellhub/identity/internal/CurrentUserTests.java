package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.identity.CurrentUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Import({ TestcontainersConfiguration.class, CurrentUserTests.Probe.class })
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CurrentUserTests {

	/** A throwaway endpoint that just echoes the resolved user. */
	@TestConfiguration
	@RestController
	@RequestMapping("/api/v1/test-probe")
	static class Probe {

		@GetMapping("/me")
		java.util.Map<String, Object> me(CurrentUser user) {
			return java.util.Map.of("id", user.id().toString(), "role", user.role().name());
		}

	}

	private final MockMvc mvc;

	CurrentUserTests(MockMvc mvc) {
		this.mvc = mvc;
	}

	@Test
	void theUserComesFromTheVerifiedToken() throws Exception {
		UUID id = UUID.randomUUID();

		mvc.perform(get("/api/v1/test-probe/me").with(jwt().jwt(j -> j.subject(id.toString()).claim("role", "ADMIN"))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void nothingTheClientSendsCanChangeWhoTheyAre() throws Exception {
		UUID real = UUID.randomUUID();
		UUID claimed = UUID.randomUUID();

		mvc.perform(get("/api/v1/test-probe/me").param("userId", claimed.toString()).param("id", claimed.toString())
				.header("X-User-Id", claimed.toString())
				.with(jwt().jwt(j -> j.subject(real.toString()).claim("role", "USER"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(real.toString()));
	}

	@Test
	void anAnonymousCallerIsRefusedBeforeTheResolverRuns() throws Exception {
		mvc.perform(get("/api/v1/test-probe/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void aTokenThatIsOddInAnyWayIsA401NotAServerError() throws Exception {
		mvc.perform(get("/api/v1/test-probe/me").with(jwt().jwt(j -> j.subject("not-a-uuid").claim("role", "USER"))))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/v1/test-probe/me").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/test-probe/me")
				.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ROOT"))))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/test-probe/me")
				.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", ""))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void onlyTheCurrentUserTypeIsHandled() {
		CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();
		org.springframework.core.MethodParameter user = org.springframework.core.MethodParameter
				.forExecutable(org.springframework.util.ReflectionUtils.findMethod(Probe.class, "me", CurrentUser.class), 0);
		org.springframework.core.MethodParameter other = org.springframework.core.MethodParameter
				.forExecutable(org.springframework.util.ReflectionUtils.findMethod(Object.class, "equals", Object.class), 0);

		assertThat(resolver.supportsParameter(user)).isTrue();
		assertThat(resolver.supportsParameter(other)).isFalse();
	}

}
