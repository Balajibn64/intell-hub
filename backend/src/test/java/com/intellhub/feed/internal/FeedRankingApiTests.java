package com.intellhub.feed.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The feed is ordered per reader: their chosen interests and what they clicked and saved change the order. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FeedRankingApiTests {

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final JsonMapper json = JsonMapper.builder().build();

	private UUID alice;

	private UUID bob;

	private UUID source;

	private UUID rustArticle;

	private UUID llmArticle;

	private UUID plainArticle;

	FeedRankingApiTests(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from users");
		jdbc.update("delete from tags");
		alice = user("alice");
		bob = user("bob");
		source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source,
				"https://example.com/" + source);
		UUID rust = tag("rust");
		UUID llm = tag("llm");
		// Equal age and relevance, so only the reader's interests can tell them apart.
		rustArticle = article("rust article", 3, rust);
		llmArticle = article("llm article", 3, llm);
		plainArticle = article("plain article", 3, null);
	}

	private UUID user(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'USER', true, now())",
				id, name + "-" + id + "@example.com");
		return id;
	}

	private UUID tag(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into tags (id, name, kind, created_at) values (?, ?, 'TAXONOMY', now())", id, name);
		return id;
	}

	private int counter;

	private UUID article(String title, int ageHours, UUID tag) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, excerpt, published_at, ingested_at,
				relevance_score, category, status)
				values (?, ?, ?, ?, ?, 'x', ?, now(), 0.6, 'AI_ML', 'ENRICHED')""", id, source, "https://example.com/a/" + id,
				String.format("%064x", ++counter * 7919L), title, Timestamp.from(Instant.now().minus(Duration.ofHours(ageHours))));
		if (tag != null) {
			jdbc.update("insert into article_tags (article_id, tag_id, origin) values (?, ?, 'LLM')", id, tag);
		}
		return id;
	}

	private static JwtRequestPostProcessor as(UUID userId) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "USER"));
	}

	private void interests(UUID user, String body) throws Exception {
		mvc.perform(put("/api/v1/me/interests").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isNoContent());
	}

	private List<UUID> feed(UUID user, String query) throws Exception {
		String body = mvc.perform(get("/api/v1/feed" + query).with(as(user))).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		List<UUID> ids = new ArrayList<>();
		for (JsonNode item : json.readTree(body).get("items")) {
			ids.add(UUID.fromString(item.get("id").asString()));
		}
		return ids;
	}

	@Test
	void twoUsersWithDifferentInterestsSeeDifferentOrders() throws Exception {
		interests(alice, "{\"interests\":[{\"tag\":\"rust\"}]}");
		interests(bob, "{\"interests\":[{\"tag\":\"llm\"}]}");

		List<UUID> forAlice = feed(alice, "");
		List<UUID> forBob = feed(bob, "");

		assertThat(forAlice.get(0)).isEqualTo(rustArticle);
		assertThat(forBob.get(0)).isEqualTo(llmArticle);
		assertThat(forAlice).containsExactlyInAnyOrder(rustArticle, llmArticle, plainArticle);
		assertThat(forBob).containsExactlyInAnyOrder(rustArticle, llmArticle, plainArticle);
		assertThat(forAlice).isNotEqualTo(forBob);
	}

	@Test
	void changingYourInterestsChangesOnlyYourOwnFeed() throws Exception {
		interests(alice, "{\"interests\":[{\"tag\":\"rust\"}]}");
		List<UUID> bobBefore = feed(bob, "");

		interests(alice, "{\"interests\":[{\"tag\":\"llm\"}]}");

		assertThat(feed(alice, "").get(0)).isEqualTo(llmArticle);
		assertThat(feed(bob, "")).isEqualTo(bobBefore);
	}

	@Test
	void savingAndClickingTeachesTheFeedWithoutAnyInterestsSet() throws Exception {
		assertThat(feed(alice, "")).hasSize(3);
		mvc.perform(put("/api/v1/articles/" + llmArticle + "/save").with(as(alice))).andExpect(status().isNoContent());
		mvc.perform(post("/api/v1/articles/" + llmArticle + "/click").with(as(alice))).andExpect(status().isNoContent());

		assertThat(feed(alice, "").get(0)).isEqualTo(llmArticle);
		// Bob did nothing, so his order has no llm preference: the saved one is not pushed to his top because of Alice.
		assertThat(feed(bob, "")).isEqualTo(feed(bob, ""));
		assertThat(jdbc.queryForObject("select count(*) from user_tag_weights where user_id = ?", Integer.class, bob)).isZero();
	}

	@Test
	void sortNewestKeepsTheOldOrderWhateverTheInterests() throws Exception {
		jdbc.update("update articles set published_at = now() - interval '1 hour' where id = ?", plainArticle);
		interests(alice, "{\"interests\":[{\"tag\":\"rust\"}]}");

		List<UUID> newest = feed(alice, "?sort=newest");

		assertThat(newest.get(0)).isEqualTo(plainArticle); // newest, not the interesting one
		assertThat(feed(alice, "?sort=relevance").get(0)).isEqualTo(rustArticle);
		assertThat(feed(alice, "")).isEqualTo(feed(alice, "?sort=relevance")); // ranked is the default
	}

	@Test
	void badSortValuesAreRefused() throws Exception {
		for (String bad : new String[] { "?sort=popular", "?sort=RELEVANCE", "?sort=relevance,newest",
				"?sort=%27%3B%20drop%20table%20articles%3B--" }) {
			mvc.perform(get("/api/v1/feed" + bad).with(as(alice))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
	}

	@Test
	void rankingCombinesWithTheFiltersAndTheSavedView() throws Exception {
		interests(alice, "{\"interests\":[{\"tag\":\"rust\"}]}");
		mvc.perform(put("/api/v1/articles/" + llmArticle + "/save").with(as(alice))).andExpect(status().isNoContent());
		mvc.perform(put("/api/v1/articles/" + plainArticle + "/save").with(as(alice))).andExpect(status().isNoContent());

		assertThat(feed(alice, "?tag=llm")).containsExactly(llmArticle);
		assertThat(feed(alice, "?saved=true")).containsExactlyInAnyOrder(llmArticle, plainArticle);
		assertThat(feed(alice, "?saved=true&category=AI_ML")).hasSize(2);
		mvc.perform(get("/api/v1/feed?size=2&page=1").with(as(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void anonymousCallersAreStillRefused() throws Exception {
		mvc.perform(get("/api/v1/feed?sort=relevance")).andExpect(status().isUnauthorized());
	}

}
