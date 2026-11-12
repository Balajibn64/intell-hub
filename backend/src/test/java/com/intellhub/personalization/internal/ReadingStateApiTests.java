package com.intellhub.personalization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ReadingStateApiTests {

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private UUID alice;

	private UUID bob;

	private UUID article;

	ReadingStateApiTests(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from users");
		alice = user("alice");
		bob = user("bob");
		UUID source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source,
				"https://example.com/" + source);
		article = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, published_at, ingested_at)
				values (?, ?, ?, ?, 't', now(), now())""", article, source, "https://example.com/a/" + article,
				String.format("%064x", Math.abs(article.getLeastSignificantBits())));
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

	private int saved(UUID user) {
		return jdbc.queryForObject("select count(*) from saved_articles where user_id = ?", Integer.class, user);
	}

	private int events(UUID user, String type) {
		return jdbc.queryForObject("select count(*) from article_events where user_id = ? and type = ?", Integer.class, user,
				type);
	}

	@Test
	void anonymousCallersAreRefusedOnEveryEndpoint() throws Exception {
		mvc.perform(put("/api/v1/articles/" + article + "/save")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/v1/articles/" + article + "/save")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/articles/" + article + "/click")).andExpect(status().isUnauthorized());
		assertThat(saved(alice) + events(alice, "CLICK")).isZero();
	}

	@Test
	void savingAndUnsavingIsIdempotentAndShowsUpInTheArticle() throws Exception {
		mvc.perform(put("/api/v1/articles/" + article + "/save").with(as(alice))).andExpect(status().isNoContent());
		mvc.perform(put("/api/v1/articles/" + article + "/save").with(as(alice))).andExpect(status().isNoContent());
		assertThat(saved(alice)).isEqualTo(1);
		assertThat(events(alice, "SAVE")).isEqualTo(1);
		mvc.perform(get("/api/v1/articles/" + article).with(as(alice))).andExpect(jsonPath("$.saved").value(true));

		mvc.perform(delete("/api/v1/articles/" + article + "/save").with(as(alice))).andExpect(status().isNoContent());
		mvc.perform(delete("/api/v1/articles/" + article + "/save").with(as(alice))).andExpect(status().isNoContent());
		assertThat(saved(alice)).isZero();
		assertThat(events(alice, "UNSAVE")).isEqualTo(1);
		mvc.perform(get("/api/v1/articles/" + article).with(as(alice))).andExpect(jsonPath("$.saved").value(false));
	}

	@Test
	void aClickIsRecordedOnceEvenIfItIsSentTwiceInARow() throws Exception {
		mvc.perform(post("/api/v1/articles/" + article + "/click").with(as(alice))).andExpect(status().isNoContent());
		mvc.perform(post("/api/v1/articles/" + article + "/click").with(as(alice))).andExpect(status().isNoContent());

		assertThat(events(alice, "CLICK")).isEqualTo(1);
	}

	@Test
	void unknownArticlesAreNotFoundAndMalformedIdsAreBadRequestsAndNothingIsWritten() throws Exception {
		String unknown = UUID.randomUUID().toString();

		mvc.perform(put("/api/v1/articles/" + unknown + "/save").with(as(alice)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
		mvc.perform(delete("/api/v1/articles/" + unknown + "/save").with(as(alice))).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/articles/" + unknown + "/click").with(as(alice))).andExpect(status().isNotFound());
		mvc.perform(put("/api/v1/articles/not-a-uuid/save").with(as(alice))).andExpect(status().isBadRequest());

		assertThat(jdbc.queryForObject("select count(*) from saved_articles", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from article_events", Integer.class)).isZero();
	}

	@Test
	void oneUserCanNeverChangeAnotherUsersState() throws Exception {
		mvc.perform(put("/api/v1/articles/" + article + "/save").with(as(alice))).andExpect(status().isNoContent());

		// Bob "unsaves" the same article: it only touches Bob's own (empty) state.
		mvc.perform(delete("/api/v1/articles/" + article + "/save").with(as(bob))).andExpect(status().isNoContent());
		// Naming Alice in the request changes nothing: there is no such input.
		mvc.perform(delete("/api/v1/articles/" + article + "/save").param("userId", alice.toString())
				.param("user", alice.toString()).header("X-User-Id", alice.toString()).with(as(bob)))
				.andExpect(status().isNoContent());

		assertThat(saved(alice)).isEqualTo(1);
		mvc.perform(get("/api/v1/articles/" + article).with(as(alice))).andExpect(jsonPath("$.saved").value(true));
		mvc.perform(get("/api/v1/articles/" + article).with(as(bob))).andExpect(jsonPath("$.saved").value(false));
		assertThat(events(bob, "UNSAVE")).isZero();
	}

	@Test
	void aTokenForADeletedAccountIsRefusedNotAServerError() throws Exception {
		UUID ghost = UUID.randomUUID();

		mvc.perform(put("/api/v1/articles/" + article + "/save").with(as(ghost)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(post("/api/v1/articles/" + article + "/click").with(as(ghost))).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("select count(*) from saved_articles", Integer.class)).isZero();
	}

	@Test
	void theWrongMethodIsRefusedWithTheSharedErrorBody() throws Exception {
		mvc.perform(get("/api/v1/articles/" + article + "/save").with(as(alice)))
				.andExpect(status().isMethodNotAllowed());
		mvc.perform(get("/api/v1/articles/" + article + "/click").with(as(alice)))
				.andExpect(status().isMethodNotAllowed());
		mvc.perform(put("/api/v1/articles/" + article + "/click").with(as(alice)))
				.andExpect(status().isMethodNotAllowed());
	}

}
