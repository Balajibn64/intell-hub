package com.intellhub.feed.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.personalization.ReadingState;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;

/** Two users with real ids, real rows, and the real security chain. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FeedApiTests {

	private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private final ReadingState readingState;

	private UUID alice;

	private UUID bob;

	private UUID sourceA;

	private UUID sourceB;

	private int counter;

	FeedApiTests(MockMvc mvc, JdbcTemplate jdbc, ReadingState readingState) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.readingState = readingState;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from users");
		alice = user("alice");
		bob = user("bob");
		sourceA = source("Source A");
		sourceB = source("Source B");
		counter = 0;
	}

	private UUID user(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'USER', true, now())",
				id, name + "-" + id + "@example.com");
		return id;
	}

	private UUID source(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', ?, ?, now())", id, name,
				"https://example.com/" + id);
		return id;
	}

	private UUID article(UUID sourceId, String title, String excerpt, String category, int secondsAfterBase) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, excerpt, category,
				published_at, ingested_at, content_sanitized)
				values (?, ?, ?, ?, ?, ?, ?, ?, now(), ?)""", id, sourceId, "https://example.com/a/" + id,
				String.format("%064x", 5_000L + (++counter)), title, excerpt, category,
				Timestamp.from(BASE.plusSeconds(secondsAfterBase)), "<p>Body of " + counter + "</p>");
		return id;
	}

	private static JwtRequestPostProcessor as(UUID userId) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("role", "USER"));
	}

	// ---- access ----

	@Test
	void anonymousCallersAreRefused() throws Exception {
		UUID id = article(sourceA, "t", "", null, 0);

		mvc.perform(get("/api/v1/feed")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(status().isUnauthorized());
	}

	@Test
	void anyRoleMayRead() throws Exception {
		article(sourceA, "visible", "", null, 0);

		mvc.perform(get("/api/v1/feed").with(jwt().jwt(j -> j.subject(alice.toString()).claim("role", "ADMIN"))))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/feed").with(as(alice))).andExpect(status().isOk());
	}

	@Test
	void responsesAreNotCacheable() throws Exception {
		UUID id = article(sourceA, "t", "", null, 0);

		MvcResult feed = mvc.perform(get("/api/v1/feed").with(as(alice))).andReturn();
		MvcResult detail = mvc.perform(get("/api/v1/articles/" + id).with(as(alice))).andReturn();

		assertThat(feed.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(detail.getResponse().getHeader("Cache-Control")).contains("no-store");
	}

	// ---- shape ----

	@Test
	void theListHasTheDocumentedShapeAndNoContentOrInternalFields() throws Exception {
		UUID id = article(sourceA, "A title", "An excerpt", "AI_ML", 10);

		MvcResult result = mvc.perform(get("/api/v1/feed").with(as(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1))
				.andExpect(jsonPath("$.items[0].id").value(id.toString()))
				.andExpect(jsonPath("$.items[0].sourceName").value("Source A"))
				.andExpect(jsonPath("$.items[0].title").value("A title"))
				.andExpect(jsonPath("$.items[0].excerpt").value("An excerpt"))
				.andExpect(jsonPath("$.items[0].category").value("AI_ML"))
				.andExpect(jsonPath("$.items[0].status").value("PENDING"))
				.andExpect(jsonPath("$.items[0].saved").value(false))
				.andExpect(jsonPath("$.items[0].url").value("https://example.com/a/" + id))
				.andExpect(jsonPath("$.items[0].contentSanitized").doesNotExist())
				.andReturn();

		String body = result.getResponse().getContentAsString().toLowerCase();
		assertThat(body).doesNotContain("hash").doesNotContain("score").doesNotContain("attempt").doesNotContain("password");
	}

	@Test
	void theDetailCarriesTheSanitizedContent() throws Exception {
		UUID id = article(sourceB, "Detail title", "ex", null, 5);

		mvc.perform(get("/api/v1/articles/" + id).with(as(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.sourceName").value("Source B"))
				.andExpect(jsonPath("$.contentSanitized").value("<p>Body of 1</p>"))
				.andExpect(jsonPath("$.saved").value(false));
	}

	@Test
	void textFieldsComeBackExactlyAsStoredBecauseTheyAreTextNotHtml() throws Exception {
		article(sourceA, "<b>Bold</b> & <script>alert(1)</script>", "<i>x</i>", null, 1);

		mvc.perform(get("/api/v1/feed").with(as(alice)))
				.andExpect(jsonPath("$.items[0].title").value("<b>Bold</b> & <script>alert(1)</script>"))
				.andExpect(jsonPath("$.items[0].excerpt").value("<i>x</i>"));
	}

	@Test
	void unknownAndMalformedIdsAreRejectedCleanly() throws Exception {
		mvc.perform(get("/api/v1/articles/" + UUID.randomUUID()).with(as(alice)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
		mvc.perform(get("/api/v1/articles/not-a-uuid").with(as(alice))).andExpect(status().isBadRequest());
	}

	// ---- paging, filters, search ----

	@Test
	void pagingWorksAndItsLimitsAreEnforced() throws Exception {
		for (int i = 0; i < 25; i++) {
			article(sourceA, "item " + i, "", null, i);
		}

		mvc.perform(get("/api/v1/feed").param("size", "10").with(as(alice)))
				.andExpect(jsonPath("$.items.length()").value(10)).andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.items[0].title").value("item 24"));
		mvc.perform(get("/api/v1/feed").param("size", "10").param("page", "2").with(as(alice)))
				.andExpect(jsonPath("$.items.length()").value(5));
		mvc.perform(get("/api/v1/feed").param("size", "10").param("page", "9").with(as(alice)))
				.andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.totalElements").value(25));
		mvc.perform(get("/api/v1/feed").with(as(alice))).andExpect(jsonPath("$.items.length()").value(20));
		for (String[] bad : new String[][] { { "size", "51" }, { "size", "0" }, { "size", "-1" }, { "size", "abc" },
				{ "page", "-1" }, { "page", "100001" }, { "page", "x" }, { "saved", "maybe" } }) {
			mvc.perform(get("/api/v1/feed").param(bad[0], bad[1]).with(as(alice))).andExpect(status().isBadRequest());
		}
	}

	@Test
	void filtersAndSearchWorkAndBadValuesAre400() throws Exception {
		article(sourceA, "Kubernetes tips", "", "SOFTWARE_ENGINEERING", 1);
		article(sourceB, "Kubernetes news", "", "INDUSTRY_STARTUPS", 2);
		article(sourceB, "Unrelated", "", "AI_ML", 3);

		mvc.perform(get("/api/v1/feed").param("q", "kubernetes").with(as(alice))).andExpect(jsonPath("$.totalElements").value(2));
		mvc.perform(get("/api/v1/feed").param("source", sourceB.toString()).with(as(alice))).andExpect(jsonPath("$.totalElements").value(2));
		mvc.perform(get("/api/v1/feed").param("category", "AI_ML").with(as(alice))).andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/api/v1/feed").param("q", "kubernetes").param("source", sourceB.toString()).with(as(alice)))
				.andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].title").value("Kubernetes news"));
		mvc.perform(get("/api/v1/feed").param("q", "x".repeat(201)).with(as(alice))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get("/api/v1/feed").param("category", "SPORTS").with(as(alice))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/feed").param("source", "not-a-uuid").with(as(alice))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/feed").param("q", "'; drop table articles; --").with(as(alice))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/feed").param("q", "a\u0000b").with(as(alice))).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select count(*) from articles", Integer.class)).isEqualTo(3);
	}

	// ---- per-user state ----

	@Test
	void theSavedFlagBelongsToTheCallerOnly() throws Exception {
		UUID a = article(sourceA, "one", "", null, 1);
		UUID b = article(sourceA, "two", "", null, 2);
		readingState.save(alice, a);

		mvc.perform(get("/api/v1/feed").with(as(alice)))
				.andExpect(jsonPath("$.items[?(@.id=='" + a + "')].saved").value(true))
				.andExpect(jsonPath("$.items[?(@.id=='" + b + "')].saved").value(false));
		mvc.perform(get("/api/v1/feed").with(as(bob)))
				.andExpect(jsonPath("$.items[?(@.id=='" + a + "')].saved").value(false));
		mvc.perform(get("/api/v1/articles/" + a).with(as(alice))).andExpect(jsonPath("$.saved").value(true));
		mvc.perform(get("/api/v1/articles/" + a).with(as(bob))).andExpect(jsonPath("$.saved").value(false));
	}

	@Test
	void theSavedFilterShowsOnlyTheCallersOwnSavedArticles() throws Exception {
		UUID a = article(sourceA, "alpha kubernetes", "", null, 1);
		UUID b = article(sourceA, "beta kubernetes", "", null, 2);
		article(sourceA, "gamma", "", null, 3);
		readingState.save(alice, a);
		readingState.save(alice, b);
		readingState.save(bob, b);

		mvc.perform(get("/api/v1/feed").param("saved", "true").with(as(alice)))
				.andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items[0].id").value(b.toString()))
				.andExpect(jsonPath("$.items[1].id").value(a.toString()));
		mvc.perform(get("/api/v1/feed").param("saved", "true").with(as(bob)))
				.andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(b.toString()));
		mvc.perform(get("/api/v1/feed").param("saved", "true").param("q", "alpha").with(as(alice)))
				.andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(a.toString()));
		mvc.perform(get("/api/v1/feed").param("saved", "false").with(as(alice))).andExpect(jsonPath("$.totalElements").value(3));
	}

	@Test
	void aUserWithNothingSavedGetsAnEmptySavedFeedNotTheWholeFeed() throws Exception {
		article(sourceA, "one", "", null, 1);

		mvc.perform(get("/api/v1/feed").param("saved", "true").with(as(bob)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void nothingTheClientSendsChangesWhoseStateIsShown() throws Exception {
		UUID a = article(sourceA, "one", "", null, 1);
		readingState.save(alice, a);

		mvc.perform(get("/api/v1/feed").param("userId", alice.toString()).header("X-User-Id", alice.toString()).with(as(bob)))
				.andExpect(jsonPath("$.items[0].saved").value(false));
	}

}
