package com.intellhub.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.ArticleCatalog;
import com.intellhub.articles.ArticleFilter;
import com.intellhub.articles.ArticlePage;
import com.intellhub.articles.ArticleView;
import com.intellhub.articles.Ranking;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/** The ranking formula seen through its effects: 0.4 relevance + 0.4 tag affinity + 0.2 recency. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ArticleRankingTests {

	private final ArticleCatalog catalog;

	private final JdbcTemplate jdbc;

	private UUID source;

	private final Map<String, UUID> tags = new HashMap<>();

	private int counter;

	ArticleRankingTests(ArticleCatalog catalog, JdbcTemplate jdbc) {
		this.catalog = catalog;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from tags");
		tags.clear();
		counter = 0;
		source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source,
				"https://example.com/" + source);
		for (String name : new String[] { "rust", "llm", "sqlite", "kubernetes" }) {
			UUID id = UUID.randomUUID();
			jdbc.update("insert into tags (id, name, kind, created_at) values (?, ?, 'TAXONOMY', now())", id, name);
			tags.put(name, id);
		}
	}

	/** An article published {@code ageHours} ago with the given scores (null = none) and tags. */
	private UUID article(String title, double ageHours, Double relevance, Double heuristic, String... tagNames) {
		UUID id = UUID.randomUUID();
		Instant published = Instant.now().minus(Duration.ofSeconds((long) (ageHours * 3600)));
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, excerpt, published_at, ingested_at,
				relevance_score, heuristic_score, category)
				values (?, ?, ?, ?, ?, 'x', ?, now(), ?, ?, 'AI_ML')""", id, source, "https://example.com/a/" + id,
				String.format("%064x", ++counter * 104729L), title, Timestamp.from(published), relevance, heuristic);
		for (String tag : tagNames) {
			jdbc.update("insert into article_tags (article_id, tag_id, origin) values (?, ?, 'LLM')", id, tags.get(tag));
		}
		return id;
	}

	private static Ranking weights(Object... pairs) {
		Map<String, Double> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
		}
		return new Ranking(map);
	}

	private ArticlePage ranked(Ranking ranking) {
		return catalog.search(new ArticleFilter(null, null, null, null, null, ranking), 0, 50);
	}

	private static List<UUID> ids(ArticlePage page) {
		return page.items().stream().map(ArticleView::id).toList();
	}

	// ---- the three parts, one at a time ----

	@Test
	void aMoreRelevantArticleComesFirstWhenEverythingElseIsEqual() {
		UUID low = article("low", 1, 0.2, null);
		UUID high = article("high", 1, 0.9, null);

		assertThat(ids(ranked(Ranking.neutral()))).containsExactly(high, low);
	}

	@Test
	void anArticleNotYetSummarizedIsRankedOnHalfItsCheapScoreAndIsNeverHidden() {
		UUID summarized = article("summarized", 1, 0.3, 0.9);
		UUID cheapOnly = article("cheap only", 1, null, 0.8); // 0.4 beats 0.3
		UUID nothing = article("nothing", 1, null, null);

		assertThat(ids(ranked(Ranking.neutral()))).containsExactly(cheapOnly, summarized, nothing);
	}

	@Test
	void anArticleWithMatchingTagsBeatsAnOtherwiseEqualOne() {
		UUID plain = article("plain", 1, 0.5, null);
		UUID tagged = article("tagged", 1, 0.5, null, "rust");

		assertThat(ids(ranked(weights("rust", 1.0)))).containsExactly(tagged, plain);
	}

	@Test
	void tagsTheReaderDoesNotCareAboutChangeNothing() {
		UUID older = article("older", 5, 0.5, null, "kubernetes");
		UUID newer = article("newer", 1, 0.5, null);

		assertThat(ids(ranked(weights("rust", 1.0)))).containsExactly(newer, older); // recency decides, as without weights
	}

	@Test
	void moreMatchingTagsHelpButWithDiminishingReturns() {
		UUID one = article("one", 1, 0.5, null, "rust");
		UUID two = article("two", 1, 0.5, null, "rust", "llm");

		assertThat(ids(ranked(weights("rust", 1.0, "llm", 1.0)))).containsExactly(two, one);

		// With a lot of weight already, a tag adds little: affinity 1-exp(-3)=0.95 vs 1-exp(-4)=0.98, which is worth
		// 0.4*0.032 = 0.013 of score, less than half a day of recency (12 hours = 0.2*0.11 = 0.022).
		jdbc.update("delete from articles");
		UUID strongOlder = article("strong older", 12, 0.5, null, "rust", "llm");
		UUID strongerNewer = article("newer", 0.5, 0.5, null, "rust");
		assertThat(ids(ranked(weights("rust", 3.0, "llm", 1.0)))).containsExactly(strongerNewer, strongOlder);
	}

	@Test
	void aNewerArticleBeatsAnOtherwiseEqualOlderOne() {
		UUID old = article("old", 100, 0.5, null);
		UUID fresh = article("fresh", 1, 0.5, null);

		assertThat(ids(ranked(Ranking.neutral()))).containsExactly(fresh, old);
	}

	@Test
	void recencyHalvesEveryThreeDaysSoAnOldButVeryRelevantArticleCanBeatAWeakNewOne() {
		// 72 hours old: 0.4*0.9 + 0 + 0.2*0.5 = 0.46 against fresh: 0.4*0.5 + 0 + 0.2 = 0.40
		UUID oldStrong = article("old strong", 72, 0.9, null);
		UUID freshWeak = article("fresh weak", 0, 0.5, null);
		// A month old: 0.4*0.9 + ~0 = 0.36, which loses to the fresh weak one.
		UUID monthOld = article("month old", 24 * 30, 0.9, null);

		assertThat(ids(ranked(Ranking.neutral()))).containsExactly(oldStrong, freshWeak, monthOld);
	}

	@Test
	void interestCanOutweighRelevanceAndRecency() {
		UUID relevantFresh = article("relevant fresh", 0, 0.9, null);
		// 0.4*0.3 + 0.4*(1-e^-2) + 0.2*0.5 = 0.12 + 0.346 + 0.1 = 0.566 > 0.4*0.9 + 0.2 = 0.56
		UUID interesting = article("interesting", 72, 0.3, null, "rust");

		assertThat(ids(ranked(weights("rust", 2.0)))).containsExactly(interesting, relevantFresh);
	}

	// ---- readers differ ----

	@Test
	void twoReadersWithDifferentInterestsSeeDifferentOrders() {
		UUID rustArticle = article("rust", 2, 0.6, null, "rust");
		UUID llmArticle = article("llm", 2, 0.6, null, "llm");
		UUID sqliteArticle = article("sqlite", 2, 0.6, null, "sqlite");

		List<UUID> forRustFan = ids(ranked(weights("rust", 1.0, "sqlite", 0.4)));
		List<UUID> forLlmFan = ids(ranked(weights("llm", 1.0, "sqlite", 0.4)));

		assertThat(forRustFan).containsExactly(rustArticle, sqliteArticle, llmArticle);
		assertThat(forLlmFan).containsExactly(llmArticle, sqliteArticle, rustArticle);
	}

	@Test
	void aReaderWithNoInterestsGetsAStableOrderFromRelevanceAndRecency() {
		for (int i = 0; i < 6; i++) {
			article("a" + i, i, 0.5, null);
		}

		assertThat(ids(ranked(Ranking.neutral()))).isEqualTo(ids(ranked(Ranking.neutral())));
		assertThat(ids(ranked(Ranking.neutral()))).hasSize(6);
	}

	// ---- combines with everything else ----

	@Test
	void rankingKeepsTheFiltersAndTheCount() {
		UUID match = article("kernel rust", 1, 0.5, null, "rust");
		article("kernel llm", 1, 0.9, null, "llm");
		article("unrelated", 1, 0.9, null, "rust");
		jdbc.update("update articles set title = 'zzz', excerpt = 'zzz' where title = 'unrelated'");

		ArticlePage tagFiltered = catalog.search(new ArticleFilter(null, null, null, "rust", null, weights("llm", 1.0)), 0, 10);
		assertThat(tagFiltered.totalElements()).isEqualTo(2);

		ArticlePage searched = catalog.search(new ArticleFilter("kernel", null, "AI_ML", "rust", null, weights("rust", 1.0)), 0, 10);
		assertThat(ids(searched)).containsExactly(match);
		assertThat(searched.totalElements()).isEqualTo(1);

		ArticlePage only = catalog.search(new ArticleFilter(null, null, null, null, Set.of(match), weights("rust", 1.0)), 0, 10);
		assertThat(ids(only)).containsExactly(match);
		assertThat(catalog.search(new ArticleFilter(null, null, null, null, Set.of(), weights("rust", 1.0)), 0, 10).items()).isEmpty();
	}

	@Test
	void pagesNeverRepeatOrSkipAnArticle() {
		List<String> tagNames = List.of("rust", "llm", "sqlite", "kubernetes");
		for (int i = 0; i < 30; i++) {
			article("a" + i, i % 7, (i % 5) / 5.0, null, tagNames.get(i % 4));
		}
		Ranking ranking = weights("rust", 1.0, "llm", 0.5);

		List<UUID> everything = ids(catalog.search(new ArticleFilter(null, null, null, null, null, ranking), 0, 50));
		List<UUID> paged = new ArrayList<>();
		for (int page = 0; page < 5; page++) {
			paged.addAll(ids(catalog.search(new ArticleFilter(null, null, null, null, null, ranking), page, 7)));
		}

		assertThat(everything).hasSize(30);
		assertThat(new HashSet<>(paged)).hasSize(30);
		assertThat(paged).isEqualTo(everything);
	}

	@Test
	void withoutARankingTheOrderIsStillNewestFirst() {
		UUID oldHighScore = article("old", 50, 0.99, null, "rust");
		UUID newLowScore = article("new", 1, 0.01, null);

		assertThat(ids(catalog.search(ArticleFilter.none(), 0, 10))).containsExactly(newLowScore, oldHighScore);
	}

	// ---- inputs ----

	@Test
	void badWeightsAreRefusedBeforeAnyQueryRuns() {
		article("a", 1, 0.5, null);
		Map<String, Double> tooMany = new HashMap<>();
		for (int i = 0; i < 101; i++) {
			tooMany.put("t" + i, 1.0);
		}
		Map<String, Double> withNull = new HashMap<>();
		withNull.put("rust", null);
		assertThatThrownBy(() -> new Ranking(withNull)).isInstanceOf(NullPointerException.class); // refused on construction

		for (Ranking bad : new Ranking[] { new Ranking(tooMany), weights("Rust", 1.0),
				weights("a b", 1.0), weights("'); drop table articles;--", 1.0), weights("rust", Double.NaN),
				weights("rust", Double.POSITIVE_INFINITY), weights("rust", -0.1), weights("rust", Ranking.MAX_WEIGHT + 1) }) {
			assertThatThrownBy(() -> ranked(bad)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(jdbc.queryForObject("select count(*) from articles", Integer.class)).isEqualTo(1);
	}

	@Test
	void zeroWeightsAndUnknownTagsAreHarmless() {
		UUID a = article("a", 1, 0.5, null, "rust");

		assertThat(ids(ranked(weights("rust", 0.0, "nosuchtag", 1.0)))).containsExactly(a);
		assertThat(ids(ranked(weights("nosuchtag", 1.0)))).containsExactly(a);
	}

	@Test
	void theHundredWeightLimitIsExactlyAllowed() {
		UUID a = article("a", 1, 0.5, null, "rust");
		Map<String, Double> hundred = new LinkedHashMap<>();
		hundred.put("rust", 1.0);
		for (int i = 1; i < 100; i++) {
			hundred.put("t" + i, 0.5);
		}

		assertThat(ids(ranked(new Ranking(hundred)))).containsExactly(a);
	}

}
