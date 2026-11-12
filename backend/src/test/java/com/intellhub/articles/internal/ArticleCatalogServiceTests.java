package com.intellhub.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.ArticleCatalog;
import com.intellhub.articles.ArticleFilter;
import com.intellhub.articles.ArticlePage;
import com.intellhub.articles.ArticleView;
import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ArticleCatalogServiceTests {

	private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

	private final ArticleCatalog catalog;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate tx;

	private UUID source;

	private UUID otherSource;

	private int counter;

	ArticleCatalogServiceTests(ArticleCatalog catalog, JdbcTemplate jdbc, TransactionTemplate tx) {
		this.catalog = catalog;
		this.jdbc = jdbc;
		this.tx = tx;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from tags");
		source = source("A");
		otherSource = source("B");
		counter = 0;
	}

	private UUID source(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', ?, ?, now())", id, name,
				"https://example.com/" + id);
		return id;
	}

	private UUID article(UUID sourceId, String title, String excerpt, String summary, String category, Instant published) {
		UUID id = UUID.randomUUID();
		int n = ++counter;
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, excerpt, summary, category,
				published_at, ingested_at, content_sanitized)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)""", id, sourceId, "https://example.com/a/" + id,
				String.format("%064x", n * 7919L + id.getLeastSignificantBits() % 1000), title, excerpt, summary, category,
				Timestamp.from(published), "<p>" + title + "</p>");
		return id;
	}

	private UUID article(String title, Instant published) {
		return article(source, title, "", null, null, published);
	}

	private static List<UUID> ids(ArticlePage page) {
		return page.items().stream().map(ArticleView::id).toList();
	}

	// ---- order and paging ----

	@Test
	void newestFirstWithTheIdAsATieBreaker() {
		UUID oldest = article("oldest", BASE);
		UUID newest = article("newest", BASE.plusSeconds(100));
		UUID tieA = article("tie a", BASE.plusSeconds(50));
		UUID tieB = article("tie b", BASE.plusSeconds(50));
		List<UUID> tied = new ArrayList<>(List.of(tieA, tieB));
		// PostgreSQL orders uuids as unsigned bytes, which matches comparing their lowercase hex text.
		// (Java's UUID.compareTo compares as signed numbers and disagrees for about half of all pairs.)
		tied.sort((x, y) -> y.toString().compareTo(x.toString()));

		List<UUID> order = ids(catalog.search(null, 0, 10));

		assertThat(order.get(0)).isEqualTo(newest);
		assertThat(order.subList(1, 3)).containsExactlyElementsOf(tied);
		assertThat(order.get(3)).isEqualTo(oldest);
		assertThat(ids(catalog.search(null, 0, 10))).isEqualTo(order);
	}

	@Test
	void pagesDoNotOverlapAndAddUpToTheTotal() {
		for (int i = 0; i < 25; i++) {
			article("item " + i, BASE.plusSeconds(i));
		}

		ArticlePage first = catalog.search(null, 0, 10);
		ArticlePage second = catalog.search(null, 1, 10);
		ArticlePage third = catalog.search(null, 2, 10);
		ArticlePage beyond = catalog.search(null, 3, 10);

		assertThat(first.items()).hasSize(10);
		assertThat(second.items()).hasSize(10);
		assertThat(third.items()).hasSize(5);
		assertThat(beyond.items()).isEmpty();
		assertThat(first.totalElements()).isEqualTo(25);
		assertThat(beyond.totalElements()).isEqualTo(25);
		assertThat(first.totalPages()).isEqualTo(3);
		Set<UUID> all = new HashSet<>();
		all.addAll(ids(first));
		all.addAll(ids(second));
		all.addAll(ids(third));
		assertThat(all).hasSize(25);
		assertThat(first.items().get(0).title()).isEqualTo("item 24");
	}

	@Test
	void pagingLimitsAreEnforced() {
		assertThatThrownBy(() -> catalog.search(null, -1, 10)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> catalog.search(null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> catalog.search(null, 0, ArticleCatalog.MAX_PAGE_SIZE + 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> catalog.search(null, ArticleCatalog.MAX_PAGE + 1, 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(catalog.search(null, 0, ArticleCatalog.MAX_PAGE_SIZE).size()).isEqualTo(50);
	}

	@Test
	void anEmptyDatabaseGivesAnEmptyPage() {
		ArticlePage page = catalog.search(null, 0, 20);

		assertThat(page.items()).isEmpty();
		assertThat(page.totalElements()).isZero();
		assertThat(page.totalPages()).isZero();
	}

	// ---- filters ----

	@Test
	void filtersBySourceCategoryAndIdsAloneAndTogether() {
		UUID a1 = article(source, "a1", "", null, "AI_ML", BASE.plusSeconds(1));
		UUID a2 = article(source, "a2", "", null, "SOFTWARE_ENGINEERING", BASE.plusSeconds(2));
		UUID b1 = article(otherSource, "b1", "", null, "AI_ML", BASE.plusSeconds(3));
		UUID none = article(otherSource, "b2", "", null, null, BASE.plusSeconds(4));

		assertThat(ids(catalog.search(new ArticleFilter(null, source, null, null), 0, 10))).containsExactly(a2, a1);
		assertThat(ids(catalog.search(new ArticleFilter(null, null, "AI_ML", null), 0, 10))).containsExactly(b1, a1);
		assertThat(ids(catalog.search(new ArticleFilter(null, source, "AI_ML", null), 0, 10))).containsExactly(a1);
		assertThat(ids(catalog.search(new ArticleFilter(null, null, null, Set.of(a1, none)), 0, 10))).containsExactly(none, a1);
		assertThat(ids(catalog.search(new ArticleFilter(null, otherSource, "AI_ML", Set.of(a1, b1)), 0, 10))).containsExactly(b1);
		assertThat(catalog.search(new ArticleFilter(null, UUID.randomUUID(), null, null), 0, 10).items()).isEmpty();
	}

	@Test
	void anEmptyIdSetMatchesNothingWhileNullMatchesEverything() {
		article("one", BASE);

		assertThat(catalog.search(new ArticleFilter(null, null, null, Set.of()), 0, 10).items()).isEmpty();
		assertThat(catalog.search(new ArticleFilter(null, null, null, Set.of()), 0, 10).totalElements()).isZero();
		assertThat(catalog.search(new ArticleFilter(null, null, null, null), 0, 10).items()).hasSize(1);
	}

	@Test
	void anUnknownCategoryIsRejected() {
		assertThatThrownBy(() -> catalog.search(new ArticleFilter(null, null, "SPORTS", null), 0, 10))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ---- full-text search ----

	@Test
	void searchFindsWordsInTheTitleTheExcerptAndTheSummary() {
		UUID inTitle = article(source, "Kubernetes operators explained", "", null, null, BASE.plusSeconds(1));
		UUID inExcerpt = article(source, "Plain title", "A deep dive into kubernetes scheduling", null, null, BASE.plusSeconds(2));
		UUID inSummary = article(source, "Another title", "", "Why kubernetes networking is hard", null, BASE.plusSeconds(3));
		article(source, "Unrelated", "Nothing to see", "Still nothing", null, BASE.plusSeconds(4));

		ArticlePage page = catalog.search(new ArticleFilter("kubernetes", null, null, null), 0, 10);

		assertThat(ids(page)).containsExactly(inSummary, inExcerpt, inTitle);
		assertThat(page.totalElements()).isEqualTo(3);
	}

	@Test
	void searchIgnoresCaseAndUnderstandsWordForms() {
		UUID running = article(source, "Running databases in production", "", null, null, BASE);

		assertThat(ids(catalog.search(new ArticleFilter("RUN DATABASE", null, null, null), 0, 10))).containsExactly(running);
		assertThat(ids(catalog.search(new ArticleFilter("  databases  ", null, null, null), 0, 10))).containsExactly(running);
	}

	@Test
	void searchSupportsWebSearchOperators() {
		UUID rust = article(source, "Rust and memory safety", "", null, null, BASE.plusSeconds(1));
		UUID go = article(source, "Go and concurrency", "", null, null, BASE.plusSeconds(2));
		UUID both = article(source, "Rust or Go for services", "", null, null, BASE.plusSeconds(3));

		assertThat(ids(catalog.search(new ArticleFilter("rust or go", null, null, null), 0, 10))).containsExactly(both, go, rust);
		assertThat(ids(catalog.search(new ArticleFilter("rust -go", null, null, null), 0, 10))).containsExactly(rust);
		assertThat(ids(catalog.search(new ArticleFilter("\"memory safety\"", null, null, null), 0, 10))).containsExactly(rust);
	}

	@Test
	void searchCombinesWithOtherFilters() {
		UUID a = article(source, "Postgres tips", "", null, "SOFTWARE_ENGINEERING", BASE.plusSeconds(1));
		article(otherSource, "Postgres internals", "", null, "SOFTWARE_ENGINEERING", BASE.plusSeconds(2));

		assertThat(ids(catalog.search(new ArticleFilter("postgres", source, "SOFTWARE_ENGINEERING", null), 0, 10)))
				.containsExactly(a);
	}

	@Test
	void odditiesInTheSearchTextNeverFailOrInject() {
		article(source, "Perfectly normal title", "", null, null, BASE);
		String[] hostile = { "'; drop table articles; --", "\" or 1=1 --", "& | ! ( ) :*", "((((", "))))", "\"unterminated",
				"\\", "\u0000", "'", "%", "_", "a:*b", "!!!", "<->", "foo & & bar", "-", "or", "and", "OR OR OR",
				"😀", "ünïcödé" };

		for (String query : hostile) {
			assertThat(catalog.search(new ArticleFilter(query, null, null, null), 0, 10)).as(query).isNotNull();
		}

		assertThat(jdbc.queryForObject("select count(*) from articles", Integer.class)).isEqualTo(1);
	}

	@Test
	void controlCharactersAndLoneSurrogatesAreCleanedOutInsteadOfCausingAServerError() {
		UUID id = article(source, "Hello world", "", null, null, BASE);

		assertThat(ids(catalog.search(new ArticleFilter("hello\u0000world", null, null, null), 0, 10))).containsExactly(id);
		assertThat(ids(catalog.search(new ArticleFilter("hello\n\t\u001bworld", null, null, null), 0, 10))).containsExactly(id);
		assertThat(ids(catalog.search(new ArticleFilter("hello\ud800 world", null, null, null), 0, 10))).containsExactly(id);
		assertThat(catalog.search(new ArticleFilter("\u0000", null, null, null), 0, 10).items()).hasSize(1);
		assertThat(ArticleCatalogService.cleanQuery("  a\u0000b\ud800c  ")).isEqualTo("a bc");
		assertThat(ArticleCatalogService.cleanQuery(null)).isEmpty();
	}

	@Test
	void aBlankQueryMeansNoSearchAndAnOverlongOneIsRejected() {
		article("one", BASE);

		assertThat(catalog.search(new ArticleFilter("   ", null, null, null), 0, 10).items()).hasSize(1);
		assertThat(catalog.search(new ArticleFilter("", null, null, null), 0, 10).items()).hasSize(1);
		assertThat(catalog.search(new ArticleFilter("x".repeat(ArticleCatalog.MAX_QUERY_LENGTH), null, null, null), 0, 10))
				.isNotNull();
		assertThatThrownBy(() -> catalog.search(new ArticleFilter("x".repeat(ArticleCatalog.MAX_QUERY_LENGTH + 1), null, null, null), 0, 10))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theSearchExpressionMatchesTheIndexSoTheIndexCanBeUsed() {
		// A typo in the expression would silently turn every search into a table scan. Generate enough rows
		// for the planner to care, forbid sequential scans, and require the index in the plan.
		List<Object[]> rows = new ArrayList<>();
		for (int i = 0; i < 3000; i++) {
			UUID id = UUID.randomUUID();
			rows.add(new Object[] { id, source, "https://example.com/bulk/" + id, String.format("%064x", 1_000_000L + i),
					"Bulk article number " + i, "excerpt text " + i, Timestamp.from(BASE.plusSeconds(i)) });
		}
		jdbc.batchUpdate("""
				insert into articles (id, source_id, canonical_url, content_hash, title, excerpt, published_at, ingested_at)
				values (?, ?, ?, ?, ?, ?, ?, now())""", rows);
		jdbc.execute("analyze articles");

		String plan = tx.execute(status -> {
			jdbc.execute("set local enable_seqscan = off");
			return String.join("\n", jdbc.queryForList("explain select id from articles where " + ArticleSql.SEARCH_VECTOR
					+ " @@ websearch_to_tsquery('english', 'number')", String.class));
		});

		assertThat(plan).contains("articles_search_idx");
	}

	// ---- what a view contains ----

	@Test
	void listItemsHaveNoContentAndDetailDoes() {
		UUID id = article("With content", BASE);

		ArticleView listed = catalog.search(null, 0, 10).items().get(0);
		ArticleView detail = catalog.find(id).orElseThrow();

		assertThat(listed.contentSanitized()).isNull();
		assertThat(detail.contentSanitized()).isEqualTo("<p>With content</p>");
		assertThat(detail.withoutContent()).isEqualTo(listed);
		assertThat(detail.publishedAt()).isEqualTo(BASE);
		assertThat(detail.status()).isEqualTo("PENDING");
	}

	@Test
	void viewsNeverCarryInternalFields() {
		List<String> names = Arrays.stream(ArticleView.class.getRecordComponents()).map(RecordComponent::getName).toList();

		assertThat(names).noneMatch(n -> n.toLowerCase().contains("hash") || n.toLowerCase().contains("score")
				|| n.toLowerCase().contains("attempt") || n.toLowerCase().contains("enrichedby"));
	}

	@Test
	void findAndExistsHandleUnknownIds() {
		UUID id = article("known", BASE);

		assertThat(catalog.existsById(id)).isTrue();
		assertThat(catalog.existsById(UUID.randomUUID())).isFalse();
		assertThat(catalog.find(UUID.randomUUID())).isEmpty();
	}

	// ---- tags ----

	private void tag(UUID article, String name, String kind) {
		jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), ?, ?, now()) on conflict (name) do nothing",
				name, kind);
		jdbc.update("insert into article_tags (article_id, tag_id, origin) select ?, id, 'LLM' from tags where name = ? on conflict do nothing",
				article, name);
	}

	@Test
	void tagsAreInListAndDetailViewsAlphabeticallyAndAreEmptyNotNullWhenThereAreNone() {
		UUID tagged = article("tagged", BASE.plusSeconds(2));
		UUID plain = article("plain", BASE.plusSeconds(1));
		tag(tagged, "zeta", "FREE");
		tag(tagged, "alpha", "TAXONOMY");
		tag(tagged, "mid", "FREE");

		ArticlePage page = catalog.search(null, 0, 10);

		assertThat(page.items().get(0).tags()).containsExactly("alpha", "mid", "zeta");
		assertThat(page.items().get(1).tags()).isEmpty();
		assertThat(catalog.find(tagged).orElseThrow().tags()).containsExactly("alpha", "mid", "zeta");
		assertThat(catalog.find(plain).orElseThrow().tags()).isNotNull().isEmpty();
	}

	@Test
	void theTagFilterKeepsOnlyArticlesWithThatTagAndCombinesWithOtherFilters() {
		UUID a = article(source, "a one", "", null, null, BASE.plusSeconds(1));
		UUID b = article(otherSource, "b two", "", null, null, BASE.plusSeconds(2));
		article(source, "c three", "", null, null, BASE.plusSeconds(3));
		tag(a, "x", "FREE");
		tag(a, "y", "FREE");
		tag(b, "y", "FREE");

		assertThat(ids(catalog.search(new ArticleFilter(null, null, null, "y", null), 0, 10))).containsExactly(b, a);
		assertThat(ids(catalog.search(new ArticleFilter(null, null, null, "x", null), 0, 10))).containsExactly(a);
		assertThat(ids(catalog.search(new ArticleFilter(null, null, null, "nope", null), 0, 10))).isEmpty();
		assertThat(ids(catalog.search(new ArticleFilter(null, source, null, "y", null), 0, 10))).containsExactly(a);
		assertThat(ids(catalog.search(new ArticleFilter("two", null, null, "y", null), 0, 10))).containsExactly(b);
		assertThat(catalog.search(new ArticleFilter(null, null, null, "y", null), 0, 10).totalElements()).isEqualTo(2);
	}

	@Test
	void aMalformedTagFilterIsRejectedBeforeItReachesTheDatabase() {
		for (String bad : new String[] { "UPPER", "has space", "'; drop table tags; --", "a".repeat(31), "", "<script>", "../x" }) {
			assertThatThrownBy(() -> catalog.search(new ArticleFilter(null, null, null, bad, null), 0, 10)).as(bad)
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void theTagListPutsCuratedTagsFirstEvenWithNoArticlesThenTheMostUsedFreeTags() {
		UUID a = article("a", BASE.plusSeconds(1));
		UUID b = article("b", BASE.plusSeconds(2));
		UUID c = article("c", BASE.plusSeconds(3));
		jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), 'curated-unused', 'TAXONOMY', now())");
		tag(a, "curated-used", "TAXONOMY");
		tag(a, "popular", "FREE");
		tag(b, "popular", "FREE");
		tag(c, "popular", "FREE");
		tag(a, "rare", "FREE");
		tag(b, "another", "FREE");
		tag(c, "another", "FREE");

		List<com.intellhub.articles.TagView> tags = catalog.tags(100);

		assertThat(tags).extracting(com.intellhub.articles.TagView::name)
				.containsExactly("curated-used", "curated-unused", "popular", "another", "rare");
		assertThat(tags.get(0).articleCount()).isEqualTo(1);
		assertThat(tags.get(1).articleCount()).isZero();
		assertThat(tags.get(2)).isEqualTo(new com.intellhub.articles.TagView("popular", "FREE", 3));
		assertThat(catalog.tags(3)).hasSize(3);
	}

	@Test
	void theTagListLimitIsEnforced() {
		assertThatThrownBy(() -> catalog.tags(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> catalog.tags(ArticleCatalog.MAX_TAG_LIST + 1)).isInstanceOf(IllegalArgumentException.class);
		assertThat(catalog.tags(ArticleCatalog.MAX_TAG_LIST)).isEmpty();
	}

}
