package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TaxonomyTests {

	private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9][a-z0-9+#.-]{0,29}$");

	private final Taxonomy taxonomy = load();

	private static Taxonomy load() {
		try {
			return new Taxonomy(JsonMapper.builder().build());
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---- the bundled file ----

	@Test
	void theBundledTaxonomyIsWellFormed() {
		List<Taxonomy.TagDefinition> tags = taxonomy.tags();

		assertThat(tags.size()).isGreaterThanOrEqualTo(25);
		assertThat(tags).allSatisfy(tag -> {
			assertThat(tag.name()).matches(VALID_NAME);
			assertThat(EnrichmentValidator.CATEGORIES).contains(tag.category());
			assertThat(tag.keywords().size()).isGreaterThanOrEqualTo(2);
			assertThat(tag.keywords()).allSatisfy(keyword -> assertThat(keyword.strip()).isEqualTo(keyword).hasSizeGreaterThan(1));
		});
		assertThat(taxonomy.names()).hasSameSizeAs(tags);
		assertThat(tags.stream().map(Taxonomy.TagDefinition::category).distinct()).containsExactlyInAnyOrder("AI_ML",
				"SOFTWARE_ENGINEERING", "INDUSTRY_STARTUPS");
	}

	@Test
	void everyTaxonomyNameIsAValidTagForTheValidator() {
		assertThat(EnrichmentValidator.cleanTags(List.copyOf(taxonomy.names()).subList(0, 5))).hasSize(5);
		assertThat(taxonomy.names()).allMatch(name -> EnrichmentValidator.cleanTags(List.of(name)).equals(List.of(name)));
	}

	// ---- matching ----

	@Test
	void keywordsMatchWholeWordsOnlyAndIgnoreCase() {
		assertThat(taxonomy.match("Rust is fast", "").hitsByTag()).containsKey("rust");
		assertThat(taxonomy.match("RUST", "").hitsByTag()).containsKey("rust");
		assertThat(taxonomy.match("We trust the process", "").hitsByTag()).doesNotContainKey("rust");
		assertThat(taxonomy.match("Javascripts everywhere", "").hitsByTag()).doesNotContainKey("javascript");
		assertThat(taxonomy.match("A new LLM", "").hitsByTag()).containsKey("llm");
		assertThat(taxonomy.match("", "Kubernetes (k8s) tips").hitsByTag()).containsKey("kubernetes");
	}

	@Test
	void multiWordAndPunctuatedKeywordsMatch() {
		assertThat(taxonomy.match("", "A large language model for code").hitsByTag()).containsKey("llm");
		assertThat(taxonomy.match("", "Migrating to node.js and ci/cd pipelines").hitsByTag()).containsKeys("javascript", "devops");
	}

	@Test
	void nothingMatchesEmptyOrUnrelatedText() {
		assertThat(taxonomy.match(null, null).hitsByTag()).isEmpty();
		assertThat(taxonomy.match("", "").hitsByTag()).isEmpty();
		assertThat(taxonomy.match("Cooking pasta", "A recipe for dinner tonight").hitsByTag()).isEmpty();
		assertThat(taxonomy.categoryFor(taxonomy.match("Cooking pasta", ""))).isNull();
	}

	@Test
	void theCategoryIsTheOneWithTheMostHits() {
		assertThat(taxonomy.categoryFor(taxonomy.match("A new LLM and neural network training data", "")))
				.isEqualTo("AI_ML");
		assertThat(taxonomy.categoryFor(taxonomy.match("Postgres query planner and database indexing", "")))
				.isEqualTo("SOFTWARE_ENGINEERING");
		assertThat(taxonomy.categoryFor(taxonomy.match("Startup raises Series A from venture capital investors", "")))
				.isEqualTo("INDUSTRY_STARTUPS");
	}

	// ---- scoring ----

	@Test
	void scoresStayBetweenZeroAndOneAndRiseWithEveryHit() {
		double none = taxonomy.score("Cooking pasta", "dinner");
		double one = taxonomy.score("", "kubernetes");
		double two = taxonomy.score("", "kubernetes and postgres");
		double many = taxonomy.score("Kubernetes postgres rust llm startup funding", "kubernetes postgres rust llm startup funding");

		assertThat(none).isZero();
		assertThat(one).isGreaterThan(none);
		assertThat(two).isGreaterThan(one);
		assertThat(many).isGreaterThan(two).isLessThanOrEqualTo(1.0);
		assertThat(taxonomy.score(null, null)).isZero();
		assertThat(taxonomy.score("", "")).isZero();
	}

	@Test
	void aHitInTheTitleCountsMoreThanOneInTheText() {
		assertThat(taxonomy.score("kubernetes", "")).isGreaterThan(taxonomy.score("", "kubernetes"));
	}

	@Test
	void aSingleTextHitClearsTheDefaultThresholdAndNoHitsDoNot() {
		assertThat(taxonomy.score("", "some words about kubernetes here")).isGreaterThan(0.15);
		assertThat(taxonomy.score("", "some words about gardening here")).isLessThan(0.15);
	}

	// ---- construction ----

	@Test
	void invalidOrDuplicateTagsAreRefusedAtStartup() {
		assertThatThrownBy(() -> new Taxonomy(List.of(new Taxonomy.TagDefinition("Bad Name", "AI_ML", List.of("x")))))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new Taxonomy(List.of(new Taxonomy.TagDefinition("same", "AI_ML", List.of("a")),
				new Taxonomy.TagDefinition("same", "AI_ML", List.of("b"))))).isInstanceOf(IllegalStateException.class);
		assertThat(new Taxonomy(List.of(new Taxonomy.TagDefinition("fine", "AI_ML", List.of("a b")))).names()).isEqualTo(Set.of("fine"));
	}

}
