package com.intellhub.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTests {

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();

	@Test
	void apiExceptionKeepsItsStatusAndCode() throws Exception {
		mvc.perform(get("/conflict"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
				.andExpect(jsonPath("$.message").value("Email already in use"))
				.andExpect(jsonPath("$.details").isEmpty());
	}

	@Test
	void validationFailureListsFields() throws Exception {
		mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.name").exists());
	}

	@Test
	void malformedJsonIsABadRequestWithTheSameBody() throws Exception {
		mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void accessDeniedIsForbidden() throws Exception {
		mvc.perform(get("/denied"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void unexpectedErrorsDoNotLeakInternals() throws Exception {
		mvc.perform(get("/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-detail"))));
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/conflict")
		String conflict() {
			throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email already in use");
		}

		@GetMapping("/denied")
		String denied() {
			throw new AccessDeniedException("nope");
		}

		@GetMapping("/boom")
		String boom() {
			throw new IllegalStateException("secret-detail");
		}

		@PostMapping("/validate")
		String validate(@Valid @RequestBody Body body) {
			return body.name();
		}

	}

	record Body(@NotBlank String name) {
	}

}
