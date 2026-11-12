package com.intellhub.retention.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminStatusApiTests {

	private static final String URL = "/api/v1/admin/status";

	private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

	private static final RequestPostProcessor USER = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private int counter;

	AdminStatusApiTests(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from llm_usage");
		counter = 0;
	}

	private UUID source(String name, String status, String error, int failures, boolean enabled) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into sources (id, type, name, url, enabled, last_fetched_at, last_status, last_error, consecutive_failures, created_at)
				values (?, 'RSS', ?, ?, ?, ?, ?, ?, ?, now())""", id, name, "https://secret-" + id + ".example.com/feed?token=abc", enabled,
				status == null ? null : Timestamp.from(Instant.now()), status, error, failures);
		return id;
	}

	private void article(UUID source, String status, Duration age) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, published_at, ingested_at, status)
				values (?, ?, ?, ?, 'Private title', ?, now(), ?)""", id, source, "https://example.com/a/" + id, String.format("%064x", ++counter * 7919L),
				Timestamp.from(Instant.now().minus(age)), status);
	}

	@Test
	void anonymousIsRefusedAndPlainUsersAreForbidden() throws Exception {
		mvc.perform(get(URL)).andExpect(status().isUnauthorized());
		mvc.perform(get(URL).with(USER)).andExpect(status().isForbidden());
	}

	@Test
	void anAdminSeesSourceHealthArticleCountsBudgetAndRetention() throws Exception {
		UUID healthy = source("Healthy blog", "OK", null, 0, true);
		source("Broken blog", "FAILED", "Timed out", 3, true);
		source("Off blog", "FAILED", "HTTP 500", 1, false);
		source("New blog", null, null, 0, true);
		article(healthy, "ENRICHED", Duration.ofDays(1));
		article(healthy, "ENRICHED", Duration.ofDays(5));
		article(healthy, "PENDING", Duration.ofDays(2));
		LocalDate day = LocalDate.now(ZoneId.of("America/Los_Angeles"));
		jdbc.update("insert into llm_usage (day, provider, calls, failures) values (?, 'gemini', 12, 2)", day);

		String body = mvc.perform(get(URL).with(ADMIN)).andExpect(status().isOk())
				.andExpect(jsonPath("$.sources.total").value(4)).andExpect(jsonPath("$.sources.enabled").value(3))
				.andExpect(jsonPath("$.sources.failing").value(1)).andExpect(jsonPath("$.sources.neverFetched").value(1))
				.andExpect(jsonPath("$.sources.items.length()").value(4))
				.andExpect(jsonPath("$.sources.items[1].name").value("Broken blog")).andExpect(jsonPath("$.sources.items[1].lastStatus").value("FAILED"))
				.andExpect(jsonPath("$.sources.items[1].lastError").value("Timed out")).andExpect(jsonPath("$.sources.items[1].consecutiveFailures").value(3))
				.andExpect(jsonPath("$.articles.total").value(3)).andExpect(jsonPath("$.articles.byStatus.ENRICHED").value(2))
				.andExpect(jsonPath("$.articles.byStatus.PENDING").value(1)).andExpect(jsonPath("$.articles.byStatus.FAILED").value(0))
				.andExpect(jsonPath("$.articles.oldestPublishedAt").exists())
				.andExpect(jsonPath("$.enrichment.enabled").value(false)).andExpect(jsonPath("$.enrichment.providers.length()").value(0))
				.andExpect(jsonPath("$.enrichment.budget.daily").value(250)).andExpect(jsonPath("$.enrichment.budget.reservedForReaders").value(50))
				.andExpect(jsonPath("$.enrichment.usageToday[0].provider").value("gemini")).andExpect(jsonPath("$.enrichment.usageToday[0].calls").value(12))
				.andExpect(jsonPath("$.retention.scheduled").value(false)).andExpect(jsonPath("$.retention.windowDays").value(7))
				.andReturn().getResponse().getContentAsString();

		// Nothing private: no addresses (or their tokens), no article titles.
		assertThat(body).doesNotContain("secret-").doesNotContain("token=abc").doesNotContain("https://").doesNotContain("Private title");
	}

	@Test
	void thePageShowsTheLastPurgeOnceOneHasRun() throws Exception {
		mvc.perform(post("/api/v1/admin/retention/purge?dryRun=false").with(ADMIN)).andExpect(status().isOk());

		mvc.perform(get(URL).with(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.retention.lastRun.dryRun").value(false))
				.andExpect(jsonPath("$.retention.lastRun.articlesExpired").isNumber()).andExpect(jsonPath("$.retention.lastRun.finishedAt").exists());

		// A dry run afterwards does not replace it.
		mvc.perform(post("/api/v1/admin/retention/purge").with(ADMIN)).andExpect(status().isOk());
		mvc.perform(get(URL).with(ADMIN)).andExpect(jsonPath("$.retention.lastRun.dryRun").value(false));
	}

	@Test
	void anEmptySystemStillAnswersWithEveryPartPresent() throws Exception {
		mvc.perform(get(URL).with(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.sources.total").value(0))
				.andExpect(jsonPath("$.sources.items.length()").value(0)).andExpect(jsonPath("$.articles.total").value(0))
				.andExpect(jsonPath("$.articles.oldestPublishedAt").doesNotExist());
	}

}
