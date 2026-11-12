package com.intellhub.retention.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.intellhub.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
class RetentionAdminApiTests {

	private static final String URL = "/api/v1/admin/retention/purge";

	private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

	private static final RequestPostProcessor USER = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));

	private final MockMvc mvc;

	private final JdbcTemplate jdbc;

	private UUID oldArticle;

	RetentionAdminApiTests(MockMvc mvc, JdbcTemplate jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		UUID source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source, "https://example.com/" + source);
		oldArticle = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, published_at, ingested_at)
				values (?, ?, ?, ?, 't', ?, now())""", oldArticle, source, "https://example.com/a/" + oldArticle,
				String.format("%064x", Math.abs(oldArticle.getLeastSignificantBits())), Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
	}

	private int articles() {
		return jdbc.queryForObject("select count(*) from articles", Integer.class);
	}

	@Test
	void anonymousCallersAreRefusedAndPlainUsersAreForbiddenAndNothingIsDeleted() throws Exception {
		mvc.perform(post(URL)).andExpect(status().isUnauthorized());
		mvc.perform(post(URL + "?dryRun=false")).andExpect(status().isUnauthorized());
		mvc.perform(post(URL).with(USER)).andExpect(status().isForbidden());
		mvc.perform(post(URL + "?dryRun=false").with(USER)).andExpect(status().isForbidden());

		assertThat(articles()).isEqualTo(1);
	}

	@Test
	void withoutTheParameterItIsADryRunAndDeletesNothing() throws Exception {
		mvc.perform(post(URL).with(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true))
				.andExpect(jsonPath("$.articlesExpired").value(1)).andExpect(jsonPath("$.articlesDeleted").value(0))
				.andExpect(jsonPath("$.articlesKeptBecauseSaved").value(0)).andExpect(jsonPath("$.tokensDeleted").value(0))
				.andExpect(jsonPath("$.durationMillis").isNumber()).andExpect(jsonPath("$.finishedAt").exists());

		mvc.perform(post(URL + "?dryRun=true").with(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));
		assertThat(articles()).isEqualTo(1);
	}

	@Test
	void dryRunFalseReallyDeletes() throws Exception {
		mvc.perform(post(URL + "?dryRun=false").with(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(false))
				.andExpect(jsonPath("$.articlesDeleted").value(1));

		assertThat(articles()).isZero();
	}

	@Test
	void aBadParameterIsA400AndDeletesNothing() throws Exception {
		// Spring would read 0, no and off as false; for a destructive call only the exact words count.
		for (String bad : new String[] { "?dryRun=maybe", "?dryRun=", "?dryRun=0", "?dryRun=no", "?dryRun=off", "?dryRun=yes", "?dryRun=1",
				"?dryRun=TRUE", "?dryRun=False", "?dryRun=true,false", "?dryRun=%20false" }) {
			mvc.perform(post(URL + bad).with(ADMIN)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}

		assertThat(articles()).isEqualTo(1);
	}

}
