package com.intellhub.ingestion.internal;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** With no secret configured the endpoint is off: nothing can authenticate, not even an empty or odd value. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "intellhub.ingestion.ingest-secret=")
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class IngestEndpointDisabledTests {

	@MockitoBean
	IngestionService ingestion;

	private final MockMvc mvc;

	IngestEndpointDisabledTests(MockMvc mvc) {
		this.mvc = mvc;
	}

	@Test
	void everyCallIsRefusedWhenNoSecretIsConfigured() throws Exception {
		for (String token : new String[] { "", " ", "anything", "null", "undefined", "test-ingest-secret-test-ingest-secret-0123" }) {
			mvc.perform(post("/internal/ingest").header(IngestTokenFilter.HEADER, token))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		}
		mvc.perform(post("/internal/ingest")).andExpect(status().isForbidden());

		verify(ingestion, never()).run();
	}

}
