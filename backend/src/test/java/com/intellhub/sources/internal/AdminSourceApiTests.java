package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Authorization is exercised with Spring's jwt() post-processor, which still runs the real filter
 * chain and method security. Real token issuing and decoding is covered by the identity tests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminSourceApiTests {

	private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

	private static final RequestPostProcessor USER = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));

	private final MockMvc mvc;

	private final SourceRepository repository;

	AdminSourceApiTests(MockMvc mvc, SourceRepository repository) {
		this.mvc = mvc;
		this.repository = repository;
	}

	@BeforeEach
	void clean() {
		repository.deleteAll();
	}

	private static String createJson(String type, String name, String url) {
		return "{\"type\":" + type + ",\"name\":" + name + ",\"url\":" + url + "}";
	}

	private static final String VALID = createJson("\"RSS\"", "\"Example\"", "\"https://example.com/feed\"");

	private String createAsAdmin(String body) throws Exception {
		String response = mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.id");
	}

	@Test
	void anonymousCallersGet401OnEveryEndpoint() throws Exception {
		String id = UUID.randomUUID().toString();
		mvc.perform(get("/api/v1/admin/sources")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/admin/sources").contentType(MediaType.APPLICATION_JSON).content(VALID))
				.andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/v1/admin/sources/" + id).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/v1/admin/sources/" + id)).andExpect(status().isUnauthorized());
	}

	@Test
	void plainUsersGet403OnEveryEndpointAndChangeNothing() throws Exception {
		String existing = createAsAdmin(VALID);

		mvc.perform(get("/api/v1/admin/sources").with(USER)).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/admin/sources").with(USER).contentType(MediaType.APPLICATION_JSON)
				.content(createJson("\"RSS\"", "\"Other\"", "\"https://other.example/feed\"")))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/api/v1/admin/sources/" + existing).with(USER).contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}")).andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/admin/sources/" + existing).with(USER)).andExpect(status().isForbidden());

		assertThat(repository.count()).isEqualTo(1);
		assertThat(repository.findAll().get(0).isEnabled()).isTrue();
	}

	@Test
	void eachTypeCanOnlyBeCreatedForItsOwnAddressAndWithValidSettings() throws Exception {
		String[][] good = { { "HN", "https://hn.algolia.com/api/v1/search?tags=front_page" },
				{ "HUGGINGFACE", "https://huggingface.co/api/daily_papers" },
				{ "GITHUB_RELEASES", "https://github.com/ollama/ollama/releases.atom" }, { "ARXIV", "https://rss.arxiv.org/rss/cs.AI" } };
		for (String[] g : good) {
			mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
					.content("{\"type\":\"" + g[0] + "\",\"name\":\"S\",\"url\":\"" + g[1] + "\",\"config\":{\"maxEntries\":20}}"))
					.andExpect(status().isCreated());
			// The same type pointed at an address of the administrator's choosing is refused.
			mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
					.content("{\"type\":\"" + g[0] + "\",\"name\":\"S\",\"url\":\"https://evil.example/feed\"}"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SOURCE_URL"));
		}
		assertThat(repository.count()).isEqualTo(4);

		String hn = mvc.perform(get("/api/v1/admin/sources").with(ADMIN)).andReturn().getResponse().getContentAsString();
		java.util.List<String> hnIds = JsonPath.read(hn, "$[?(@.type=='HN')].id");
		String hnId = hnIds.get(0);
		mvc.perform(patch("/api/v1/admin/sources/" + hnId).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"config\":{\"minPoints\":-3}}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SOURCE_CONFIG"));
		mvc.perform(patch("/api/v1/admin/sources/" + hnId).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"config\":{\"minPoints\":120,\"maxEntries\":30}}")).andExpect(status().isOk());
		mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content(createJson("\"RSS\"", "\"E\"", "\"https://example.com/feed2\"").replace("}", ",\"config\":{\"maxEntries\":0}}")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SOURCE_CONFIG"));
	}

	@Test
	void adminCanCreateAndListWithHealthFields() throws Exception {
		mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"RSS\",\"name\":\" Example \",\"url\":\"https://Example.com/feed\",\"config\":{\"k\":\"v\"}}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").exists())
				.andExpect(jsonPath("$.type").value("RSS"))
				.andExpect(jsonPath("$.name").value("Example"))
				.andExpect(jsonPath("$.url").value("https://example.com/feed"))
				.andExpect(jsonPath("$.config.k").value("v"))
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.consecutiveFailures").value(0))
				.andExpect(jsonPath("$.lastStatus").doesNotExist())
				.andExpect(jsonPath("$.lastFetchedAt").doesNotExist());

		mvc.perform(get("/api/v1/admin/sources").with(ADMIN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Example"));
	}

	@Test
	void duplicatesAreConflicts() throws Exception {
		createAsAdmin(VALID);

		mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(VALID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SOURCE_EXISTS"));
	}

	@Test
	void unsafeUrlsAreRejectedWithoutBeingEchoed() throws Exception {
		for (String url : new String[] { "http://169.254.169.254/latest/meta-data/", "http://localhost:8080/admin",
				"http://10.0.0.1/", "file:///etc/passwd", "https://user:secret@example.com/feed", "http://2130706433/" }) {
			String body = mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
					.content(createJson("\"RSS\"", "\"X\"", "\"" + url + "\"")))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_SOURCE_URL"))
					.andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain("secret").doesNotContain("169.254");
		}
		assertThat(repository.count()).isZero();
	}

	@Test
	void missingOrBadFieldsAreValidationErrors() throws Exception {
		mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.type").exists())
				.andExpect(jsonPath("$.details.name").exists())
				.andExpect(jsonPath("$.details.url").exists());
		mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content(createJson("\"PODCAST\"", "\"X\"", "\"https://example.com/feed\"")))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"RSS\",\"name\":\"X\",\"url\":\"https://example.com/feed\",\"config\":[1,2]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void oversizedConfigIsRejected() throws Exception {
		String config = "{\"blob\":\"" + "x".repeat(5000) + "\"}";

		mvc.perform(post("/api/v1/admin/sources").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"RSS\",\"name\":\"X\",\"url\":\"https://example.com/feed\",\"config\":" + config + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SOURCE_CONFIG"));
	}

	@Test
	void patchRenamesAndDisables() throws Exception {
		String id = createAsAdmin(VALID);

		mvc.perform(patch("/api/v1/admin/sources/" + id).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Renamed\",\"enabled\":false,\"config\":{\"a\":1}}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Renamed"))
				.andExpect(jsonPath("$.enabled").value(false))
				.andExpect(jsonPath("$.config.a").value(1))
				.andExpect(jsonPath("$.url").value("https://example.com/feed"))
				.andExpect(jsonPath("$.type").value("RSS"));
	}

	@Test
	void patchCannotChangeTheTypeOrTheUrl() throws Exception {
		String id = createAsAdmin(VALID);

		mvc.perform(patch("/api/v1/admin/sources/" + id).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://evil.example/feed\",\"type\":\"HN\",\"name\":\"Sneaky\"}"));

		Source stored = repository.findById(UUID.fromString(id)).orElseThrow();
		assertThat(stored.getUrl()).isEqualTo("https://example.com/feed");
		assertThat(stored.getType().name()).isEqualTo("RSS");
	}

	@Test
	void unknownIdsAreNotFoundAndMalformedIdsAreBadRequests() throws Exception {
		String missing = UUID.randomUUID().toString();

		mvc.perform(patch("/api/v1/admin/sources/" + missing).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
		mvc.perform(delete("/api/v1/admin/sources/" + missing).with(ADMIN)).andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/admin/sources/not-a-uuid").with(ADMIN)).andExpect(status().isBadRequest());
	}

	@Test
	void deleteRemovesTheSourceAndSecondDeleteIsNotFound() throws Exception {
		String id = createAsAdmin(VALID);

		mvc.perform(delete("/api/v1/admin/sources/" + id).with(ADMIN)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/v1/admin/sources/" + id).with(ADMIN)).andExpect(status().isNotFound());
		assertThat(repository.count()).isZero();
	}

}
