package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.ingestion.internal.IngestSummary.SourceResult;
import com.intellhub.ingestion.internal.IngestSummary.Status;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The service is mocked, so nothing here can fetch anything. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "intellhub.ingestion.ingest-secret=" + IngestEndpointTests.SECRET)
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class IngestEndpointTests {

	static final String SECRET = "test-ingest-secret-test-ingest-secret-0123";

	@MockitoBean
	IngestionService ingestion;

	private final MockMvc mvc;

	IngestEndpointTests(MockMvc mvc) {
		this.mvc = mvc;
	}

	@BeforeEach
	void stub() {
		UUID id = UUID.randomUUID();
		when(ingestion.run()).thenReturn(new IngestSummary(Instant.parse("2026-11-12T10:00:00Z"), 123, 1, 1, 0, 0, 3, 2, 1,
				0, List.of(new SourceResult(id, "A source", Status.OK, null, 6, 3, 2, 1, 0))));
	}

	@Test
	void theCorrectSecretRunsIngestionAndReturnsTheSummary() throws Exception {
		MvcResult result = mvc.perform(post("/internal/ingest").header(IngestTokenFilter.HEADER, SECRET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourcesTotal").value(1))
				.andExpect(jsonPath("$.articlesCreated").value(3))
				.andExpect(jsonPath("$.duplicates").value(2))
				.andExpect(jsonPath("$.sources[0].name").value("A source"))
				.andExpect(jsonPath("$.sources[0].status").value("OK"))
				.andReturn();

		verify(ingestion, times(1)).run();
		assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET);
		assertThat(result.getResponse().getHeaderNames()).noneMatch(h -> h.equalsIgnoreCase("Set-Cookie"));
	}

	@Test
	void everyWrongOrMissingSecretGetsTheSame403AndNeverRunsIngestion() throws Exception {
		String[] wrong = { "", " ", "wrong", SECRET.substring(1), SECRET + "x", SECRET.toUpperCase(), " " + SECRET,
				SECRET + " ", "Bearer " + SECRET, "x".repeat(5000), "null" };
		String first = null;
		for (String token : wrong) {
			MvcResult result = mvc.perform(post("/internal/ingest").header(IngestTokenFilter.HEADER, token))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("FORBIDDEN"))
					.andReturn();
			String body = result.getResponse().getContentAsString();
			first = first == null ? body : first;
			assertThat(body).isEqualTo(first);
		}
		MvcResult missing = mvc.perform(post("/internal/ingest")).andExpect(status().isForbidden()).andReturn();
		assertThat(missing.getResponse().getContentAsString()).isEqualTo(first);

		verify(ingestion, never()).run();
	}

	@Test
	void aUserTokenIsNotAcceptedHereEvenForAnAdmin() throws Exception {
		mvc.perform(post("/internal/ingest").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isForbidden());
		mvc.perform(post("/internal/ingest").header("Authorization", "Bearer not-a-real-token"))
				.andExpect(status().isForbidden());

		verify(ingestion, never()).run();
	}

	@Test
	void onlyPostToTheIngestPathIsAllowedEvenWithTheSecret() throws Exception {
		mvc.perform(get("/internal/ingest").header(IngestTokenFilter.HEADER, SECRET)).andExpect(status().isForbidden());
		mvc.perform(delete("/internal/ingest").header(IngestTokenFilter.HEADER, SECRET)).andExpect(status().isForbidden());
		mvc.perform(post("/internal/other").header(IngestTokenFilter.HEADER, SECRET)).andExpect(status().isForbidden());
		mvc.perform(post("/internal/ingest/extra").header(IngestTokenFilter.HEADER, SECRET))
				.andExpect(status().isForbidden());
		mvc.perform(post("/internal").header(IngestTokenFilter.HEADER, SECRET)).andExpect(status().isForbidden());

		verify(ingestion, never()).run();
	}

	@Test
	void theSecretDoesNotOpenAnythingOutsideInternal() throws Exception {
		mvc.perform(get("/api/v1/me").header(IngestTokenFilter.HEADER, SECRET)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/admin/sources").header(IngestTokenFilter.HEADER, SECRET))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void aRunAlreadyInProgressIsA409() throws Exception {
		when(ingestion.run()).thenThrow(new IngestAlreadyRunningException());

		mvc.perform(post("/internal/ingest").header(IngestTokenFilter.HEADER, SECRET))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INGEST_RUNNING"));
	}

}
