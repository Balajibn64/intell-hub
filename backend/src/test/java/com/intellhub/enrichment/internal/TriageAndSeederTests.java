package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.ArticleEnrichment;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TriageAndSeederTests {

	private final ArticleEnrichment articles;

	private final com.intellhub.articles.EnrichmentRequests requests;

	private final Taxonomy taxonomy;

	private final JdbcTemplate jdbc;

	private UUID source;

	private int counter;

	TriageAndSeederTests(ArticleEnrichment articles, com.intellhub.articles.EnrichmentRequests requests, Taxonomy taxonomy,
			JdbcTemplate jdbc) {
		this.articles = articles;
		this.requests = requests;
		this.taxonomy = taxonomy;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from tags");
		source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source,
				"https://example.com/" + source);
		counter = 0;
	}

	private static EnrichmentProperties props(double threshold, boolean seed) {
		return new EnrichmentProperties("fake", 250, 50, 5, 3, Duration.ofSeconds(30), Duration.ofMinutes(10), 20, ZoneId.of("UTC"),
				5000, threshold, seed);
	}

	private UUID article(String title, String excerpt) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, excerpt, published_at, ingested_at)
				values (?, ?, ?, ?, ?, ?, ?, now())""", id, source, "https://example.com/a/" + id,
				String.format("%064x", 70_000L + (++counter)), title, excerpt, Timestamp.from(Instant.parse("2026-11-01T00:00:00Z").plusSeconds(counter)));
		return id;
	}

	private String status(UUID id) {
		return jdbc.queryForObject("select status from articles where id = ?", String.class, id);
	}

	private Double score(UUID id) {
		return jdbc.queryForObject("select heuristic_score from articles where id = ?", Double.class, id);
	}

	// ---- triage ----

	@Test
	void relevantArticlesStayPendingWithAScoreAndIrrelevantOnesAreOnlyShownWithTheirExcerpt() {
		UUID relevant = article("Kubernetes operators for Postgres", "How to run a database on kubernetes.");
		UUID irrelevant = article("Ten pasta recipes", "Dinner ideas for the whole family.");

		int handled = new Triage(articles, taxonomy, props(0.15, false)).run(100);

		assertThat(handled).isEqualTo(2);
		assertThat(status(relevant)).isEqualTo("PENDING");
		assertThat(score(relevant)).isGreaterThan(0.15);
		assertThat(status(irrelevant)).isEqualTo("EXCERPT_ONLY");
		assertThat(score(irrelevant)).isZero();
	}

	@Test
	void theThresholdDecidesWhereTheLineIs() {
		UUID borderline = article("Some words", "mentions kubernetes once");

		new Triage(articles, taxonomy, props(0.9, false)).run(100);
		assertThat(status(borderline)).isEqualTo("EXCERPT_ONLY");

		UUID again = article("Some words", "mentions kubernetes once");
		new Triage(articles, taxonomy, props(0.0, false)).run(100);
		assertThat(status(again)).isEqualTo("PENDING");
	}

	@Test
	void triageHandlesAtMostTheLimitAndNeverRescoresAnArticle() {
		for (int i = 0; i < 6; i++) {
			article("Rust tips " + i, "rust");
		}
		Triage triage = new Triage(articles, taxonomy, props(0.15, false));

		assertThat(triage.run(4)).isEqualTo(4);
		assertThat(triage.run(4)).isEqualTo(2);
		assertThat(triage.run(4)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from articles where heuristic_score is not null", Integer.class)).isEqualTo(6);
	}

	@Test
	void triageCostsNoBudget() {
		article("Kubernetes", "kubernetes");

		new Triage(articles, taxonomy, props(0.15, false)).run(100);

		assertThat(jdbc.queryForObject("select count(*) from llm_usage", Integer.class)).isZero();
	}

	@Test
	void aSkippedArticleCanStillBeRequestedAndThenBecomesWorkFirstInLine() {
		UUID skipped = article("Ten pasta recipes", "Dinner ideas.");
		article("Kubernetes news", "kubernetes everywhere");
		new Triage(articles, taxonomy, props(0.15, false)).run(100);

		assertThat(requests.requestEnrichment(skipped)).isTrue();

		assertThat(articles.claimBatch(1, com.intellhub.articles.ClaimKind.REQUESTED)).extracting(c -> c.id()).containsExactly(skipped);
	}

	// ---- seeder ----

	@Test
	void theSeederCreatesEveryCuratedTagOnceAndCanBeRepeated() {
		TaxonomySeeder seeder = new TaxonomySeeder(taxonomy, articles, props(0.15, true));

		seeder.run(null);
		seeder.run(null);

		assertThat(jdbc.queryForObject("select count(*) from tags where kind = 'TAXONOMY'", Integer.class)).isEqualTo(taxonomy.names().size());
		assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isEqualTo(taxonomy.names().size());
	}

	@Test
	void theSeederPromotesAFreeTagThatMatchesACuratedName() {
		jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), 'rust', 'FREE', now())");

		new TaxonomySeeder(taxonomy, articles, props(0.15, true)).run(null);

		assertThat(jdbc.queryForObject("select kind from tags where name = 'rust'", String.class)).isEqualTo("TAXONOMY");
	}

	@Test
	void theSeederDoesNothingWhenSwitchedOff() {
		new TaxonomySeeder(taxonomy, articles, props(0.15, false)).run(null);

		assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isZero();
	}

}
