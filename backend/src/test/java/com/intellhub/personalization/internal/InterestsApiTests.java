package com.intellhub.personalization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.personalization.UserInterests;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InterestsApiTests {

	private static final String URL = "/api/v1/me/interests";

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final UserInterests interests;

	private UUID alice;

	private UUID bob;

	InterestsApiTests(MockMvc mvc, JdbcTemplate jdbc, UserInterests interests) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.interests = interests;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from users");
		jdbc.update("delete from tags");
		for (String tag : new String[] { "llm", "rust", "sqlite", "kubernetes" }) {
			jdbc.update("insert into tags (id, name, kind, created_at) values (?, ?, 'TAXONOMY', now())", UUID.randomUUID(), tag);
		}
		alice = user("alice");
		bob = user("bob");
	}

	private UUID user(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'USER', true, now())",
				id, name + "-" + id + "@example.com");
		return id;
	}

	private static JwtRequestPostProcessor as(UUID userId) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "USER"));
	}

	private void putInterests(UUID user, String json, int expected) throws Exception {
		mvc.perform(put(URL).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().is(expected));
	}

	@Test
	void anonymousCallersAreRefused() throws Exception {
		mvc.perform(get(URL)).andExpect(status().isUnauthorized());
		mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"interests\":[]}")).andExpect(status().isUnauthorized());
	}

	@Test
	void aUserWithNothingSeesAnEmptyList() throws Exception {
		mvc.perform(get(URL).with(as(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void chosenInterestsAreStoredAndReadBackStrongestFirstWithDefaultWeightOne() throws Exception {
		putInterests(alice, "{\"interests\":[{\"tag\":\"rust\",\"weight\":0.4},{\"tag\":\"llm\"}]}", 204);

		mvc.perform(get(URL).with(as(alice))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].tag").value("llm")).andExpect(jsonPath("$[0].explicit").value(1.0))
				.andExpect(jsonPath("$[0].learned").value(0.0))
				.andExpect(jsonPath("$[1].tag").value("rust")).andExpect(jsonPath("$[1].explicit").value(0.4));
	}

	@Test
	void putReplacesTheWholeSetAndAnEmptyListClearsIt() throws Exception {
		putInterests(alice, "{\"interests\":[{\"tag\":\"rust\"},{\"tag\":\"llm\"}]}", 204);
		putInterests(alice, "{\"interests\":[{\"tag\":\"sqlite\",\"weight\":0.5}]}", 204);

		mvc.perform(get(URL).with(as(alice))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].tag").value("sqlite"));

		putInterests(alice, "{\"interests\":[]}", 204);
		mvc.perform(get(URL).with(as(alice))).andExpect(jsonPath("$.length()").value(0));
		assertThat(jdbc.queryForObject("select count(*) from user_tag_weights where user_id = ?", Integer.class, alice)).isZero();
	}

	@Test
	void changingAWeightKeepsTheTagAndDuplicatesKeepTheHighest() throws Exception {
		putInterests(alice, "{\"interests\":[{\"tag\":\"rust\",\"weight\":0.3},{\"tag\":\"rust\",\"weight\":0.9}]}", 204);

		mvc.perform(get(URL).with(as(alice))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].explicit").value(0.9));
	}

	@Test
	void usersNeverSeeOrChangeEachOthersInterests() throws Exception {
		putInterests(alice, "{\"interests\":[{\"tag\":\"rust\"}]}", 204);
		putInterests(bob, "{\"interests\":[{\"tag\":\"llm\"}]}", 204);

		mvc.perform(get(URL).with(as(alice))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].tag").value("rust"));
		mvc.perform(get(URL).with(as(bob))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].tag").value("llm"));
		// A body cannot name another user: an unknown field is ignored and the caller's own set is the one replaced.
		putInterests(bob, "{\"userId\":\"" + alice + "\",\"interests\":[{\"tag\":\"sqlite\"}]}", 204);
		mvc.perform(get(URL).with(as(alice))).andExpect(jsonPath("$[0].tag").value("rust"));
	}

	@Test
	void unknownTagsAreRejectedAndNothingIsChanged() throws Exception {
		putInterests(alice, "{\"interests\":[{\"tag\":\"rust\"}]}", 204);

		mvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"interests\":[{\"tag\":\"llm\"},{\"tag\":\"nosuchtag\"}]}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_TAG"));

		mvc.perform(get(URL).with(as(alice))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].tag").value("rust"));
	}

	@Test
	void badInputIsA400() throws Exception {
		String tooMany = "{\"interests\":[" + "{\"tag\":\"rust\"},".repeat(50) + "{\"tag\":\"llm\"}]}"; // 51
		for (String bad : new String[] { "{}", "{\"interests\":null}", "{\"interests\":[null]}", "{\"interests\":[{}]}",
				"{\"interests\":[{\"tag\":\"Rust\"}]}", "{\"interests\":[{\"tag\":\"a b\"}]}", "{\"interests\":[{\"tag\":\"'; drop table tags;--\"}]}",
				"{\"interests\":[{\"tag\":\"rust\",\"weight\":0}]}", "{\"interests\":[{\"tag\":\"rust\",\"weight\":0.09}]}",
				"{\"interests\":[{\"tag\":\"rust\",\"weight\":1.1}]}", "{\"interests\":[{\"tag\":\"rust\",\"weight\":-1}]}",
				"{\"interests\":[{\"tag\":\"rust\",\"weight\":NaN}]}", "{\"interests\":[{\"tag\":\"rust\",\"weight\":\"high\"}]}",
				"not json", tooMany }) {
			putInterests(alice, bad, 400);
		}
		assertThat(jdbc.queryForObject("select count(*) from user_tag_weights", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isEqualTo(4);
	}

	// ---- learned weights are shown faded ----

	@Test
	void learnedWeightsAreListedFadedByHalfEveryTwoWeeksAndTinyOnesAreHidden() throws Exception {
		UUID rust = tag("rust");
		UUID llm = tag("llm");
		UUID sqlite = tag("sqlite");
		jdbc.update("insert into user_tag_weights values (?, ?, 0, 0.8, now() - interval '14 days')", alice, rust);
		jdbc.update("insert into user_tag_weights values (?, ?, 0, 0.8, now())", alice, llm);
		jdbc.update("insert into user_tag_weights values (?, ?, 0, 0.1, now() - interval '28 days')", alice, sqlite); // 0.025: hidden

		var result = mvc.perform(get(URL).with(as(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andReturn();

		var body = new tools.jackson.databind.json.JsonMapper().readTree(result.getResponse().getContentAsString());
		assertThat(body.get(0).get("tag").asString()).isEqualTo("llm");
		assertThat(body.get(0).get("learned").asDouble()).isBetween(0.79, 0.8);
		assertThat(body.get(1).get("tag").asString()).isEqualTo("rust");
		assertThat(body.get(1).get("learned").asDouble()).isBetween(0.39, 0.41);
	}

	@Test
	void choosingATagDoesNotRestartTheFadingOfWhatWasLearned() throws Exception {
		UUID rust = tag("rust");
		jdbc.update("insert into user_tag_weights values (?, ?, 0, 0.8, now() - interval '14 days')", alice, rust);

		putInterests(alice, "{\"interests\":[{\"tag\":\"rust\",\"weight\":0.5}]}", 204);

		var weights = interests.rankingWeights(alice);
		assertThat(weights.get("rust")).isBetween(0.89, 0.91); // 0.5 chosen + 0.4 faded, not 0.5 + 0.8
	}

	@Test
	void droppingATagKeepsWhatWasLearnedForIt() throws Exception {
		UUID rust = tag("rust");
		jdbc.update("insert into user_tag_weights values (?, ?, 1.0, 0.6, now())", alice, rust);

		putInterests(alice, "{\"interests\":[]}", 204);

		assertThat(interests.rankingWeights(alice)).containsOnlyKeys("rust");
		assertThat(interests.rankingWeights(alice).get("rust")).isBetween(0.59, 0.6);
	}

	@Test
	void rankingWeightsAreEffectiveWeightsStrongestFirstAndAtMostAHundred() {
		for (int i = 0; i < 120; i++) {
			UUID id = UUID.randomUUID();
			jdbc.update("insert into tags (id, name, kind, created_at) values (?, ?, 'FREE', now())", id, "t" + i);
			jdbc.update("insert into user_tag_weights values (?, ?, ?, 0, now())", alice, id, 0.1 + i * 0.007);
		}

		var weights = interests.rankingWeights(alice);

		assertThat(weights).hasSize(100);
		assertThat(weights.keySet().iterator().next()).isEqualTo("t119");
		assertThat(weights).doesNotContainKey("t0");
	}

	@Test
	void theDatabaseRefusesOutOfRangeWeights() {
		UUID rust = tag("rust");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("insert into user_tag_weights values (?, ?, 1.5, 0, now())", alice, rust))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("insert into user_tag_weights values (?, ?, 0, -0.1, now())", alice, rust))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void deletingAUserOrATagRemovesTheirWeights() {
		UUID rust = tag("rust");
		UUID llm = tag("llm");
		jdbc.update("insert into user_tag_weights values (?, ?, 1, 0, now())", alice, rust);
		jdbc.update("insert into user_tag_weights values (?, ?, 1, 0, now())", bob, rust);
		jdbc.update("insert into user_tag_weights values (?, ?, 1, 0, now())", bob, llm);

		jdbc.update("delete from users where id = ?", alice);
		assertThat(jdbc.queryForObject("select count(*) from user_tag_weights", Integer.class)).isEqualTo(2);
		jdbc.update("delete from tags where id = ?", rust);
		assertThat(jdbc.queryForObject("select count(*) from user_tag_weights", Integer.class)).isEqualTo(1);
	}

	@Test
	void replacingInterestsWhileClickingAndSavingNeverDeadlocks() throws Exception {
		UUID article1 = articleWithTags("rust", "llm", "sqlite", "kubernetes");
		UUID article2 = articleWithTags("kubernetes", "sqlite", "llm", "rust");
		var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
		java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
		String forward = "{\"interests\":[{\"tag\":\"rust\"},{\"tag\":\"llm\"},{\"tag\":\"sqlite\"},{\"tag\":\"kubernetes\"}]}";
		String backward = "{\"interests\":[{\"tag\":\"kubernetes\"},{\"tag\":\"sqlite\"},{\"tag\":\"llm\"},{\"tag\":\"rust\"}]}";
		for (int worker = 0; worker < 4; worker++) {
			final int w = worker;
			futures.add(pool.submit(() -> {
				for (int i = 0; i < 25; i++) {
					if (w % 2 == 0) {
						mvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON).content(i % 2 == 0 ? forward : backward))
								.andExpect(status().isNoContent());
					}
					else {
						mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
								.put("/api/v1/articles/" + (i % 2 == 0 ? article1 : article2) + "/save").with(as(alice))).andExpect(status().isNoContent());
						mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
								.delete("/api/v1/articles/" + (i % 2 == 0 ? article1 : article2) + "/save").with(as(alice))).andExpect(status().isNoContent());
					}
				}
				return null;
			}));
		}
		for (var f : futures) {
			f.get(60, java.util.concurrent.TimeUnit.SECONDS); // an exception (a deadlock) surfaces here
		}
		pool.shutdown();
		assertThat(interests.rankingWeights(alice)).containsOnlyKeys("rust", "llm", "sqlite", "kubernetes");
	}

	private UUID articleWithTags(String... names) {
		UUID source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source, "https://example.com/" + source);
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, published_at, ingested_at)
				values (?, ?, ?, ?, 't', now(), now())""", id, source, "https://example.com/a/" + id,
				String.format("%064x", Math.abs(id.getLeastSignificantBits())));
		for (String name : names) {
			jdbc.update("insert into article_tags (article_id, tag_id, origin) values (?, ?, 'LLM')", id, tag(name));
		}
		return id;
	}

	@Test
	void whatTheListShowsIsWhatRanksTheFeed() throws Exception {
		jdbc.update("insert into user_tag_weights values (?, ?, 0, 0.03, now())", alice, tag("rust")); // below the threshold
		jdbc.update("insert into user_tag_weights values (?, ?, 0, 0.3, now())", alice, tag("llm"));

		assertThat(interests.rankingWeights(alice)).containsOnlyKeys("llm");
		mvc.perform(get(URL).with(as(alice))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].tag").value("llm"));
	}

	private UUID tag(String name) {
		return jdbc.queryForObject("select id from tags where name = ?", UUID.class, name);
	}

}
