package com.intellhub.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.ArticleStore;
import com.intellhub.articles.NewArticle;
import com.intellhub.articles.StoreOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ArticleStoreServiceTests {

	private static final Instant PUBLISHED = Instant.parse("2026-01-01T00:00:00Z");

	private static final String HASH_A = "a".repeat(64);

	private static final String HASH_B = "b".repeat(64);

	private final ArticleStore store;

	private final ArticleRepository repository;

	private final JdbcTemplate jdbc;

	private UUID sourceId;

	ArticleStoreServiceTests(ArticleStore store, ArticleRepository repository, JdbcTemplate jdbc) {
		this.store = store;
		this.repository = repository;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		sourceId = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'Test', ?, now())",
				sourceId, "https://example.com/" + sourceId);
	}

	private NewArticle article(String url, String hash) {
		return new NewArticle(sourceId, url, hash, "A title", "An Author", PUBLISHED, "An excerpt.", "<p>Body</p>");
	}

	@Test
	void aNewArticleIsStoredAsPendingWithTheIngestTime() {
		Instant before = Instant.now();

		StoreOutcome outcome = store.store(article("https://example.com/a", HASH_A));

		assertThat(outcome).isEqualTo(StoreOutcome.CREATED);
		Article saved = repository.findAll().get(0);
		assertThat(saved.getStatus()).isEqualTo(ArticleStatus.PENDING);
		assertThat(saved.getEnrichmentAttempts()).isZero();
		assertThat(saved.getTitle()).isEqualTo("A title");
		assertThat(saved.getContentSanitized()).isEqualTo("<p>Body</p>");
		assertThat(saved.getPublishedAt()).isEqualTo(PUBLISHED);
		assertThat(saved.getIngestedAt()).isAfterOrEqualTo(before.minusSeconds(1));
	}

	@Test
	void storingTheSameArticleAgainIsADuplicateAndChangesNothing() {
		store.store(article("https://example.com/a", HASH_A));

		assertThat(store.store(article("https://example.com/a", HASH_A))).isEqualTo(StoreOutcome.DUPLICATE_URL);
		assertThat(store.store(article("https://example.com/a", HASH_B))).isEqualTo(StoreOutcome.DUPLICATE_URL);
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void theSameContentUnderAnotherUrlIsADuplicate() {
		store.store(article("https://example.com/a", HASH_A));

		assertThat(store.store(article("https://mirror.example/copy", HASH_A))).isEqualTo(StoreOutcome.DUPLICATE_CONTENT);
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void concurrentStoresOfTheSameUrlCreateExactlyOne() throws Exception {
		int threads = 8;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<StoreOutcome>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				// Different hashes, so only the URL constraint can stop the duplicates.
				String hash = String.format("%064x", i + 1);
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return store.store(article("https://example.com/race", hash));
				}));
			}
			ready.await();
			go.countDown();
			int created = 0;
			for (Future<StoreOutcome> future : futures) {
				StoreOutcome outcome = future.get();
				assertThat(outcome).isIn(StoreOutcome.CREATED, StoreOutcome.DUPLICATE_URL, StoreOutcome.DUPLICATE_CONTENT);
				if (outcome == StoreOutcome.CREATED) {
					created++;
				}
			}
			assertThat(created).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void invalidArticlesAreRejectedBeforeAnythingIsStored() {
		assertThatThrownBy(() -> store.store(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.store(new NewArticle(null, "https://e.com/a", HASH_A, "t", null, PUBLISHED, "", "")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.store(article("https://e.com/" + "x".repeat(NewArticle.MAX_URL), HASH_A)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.store(article("https://e.com/a", "not-a-hash")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.store(new NewArticle(sourceId, "https://e.com/a", HASH_A,
				"t".repeat(NewArticle.MAX_TITLE + 1), null, PUBLISHED, "", "")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.store(new NewArticle(sourceId, "https://e.com/a", HASH_A, " ", null, PUBLISHED, "", "")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.store(new NewArticle(sourceId, "https://e.com/a", HASH_A, "t", null, PUBLISHED,
				"e".repeat(NewArticle.MAX_EXCERPT + 1), "")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.store(new NewArticle(sourceId, "https://e.com/a", HASH_A, "t", null, PUBLISHED, "",
				"c".repeat(NewArticle.MAX_CONTENT + 1))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.store(new NewArticle(sourceId, "https://e.com/a", HASH_A, "t", null, null, "", "")))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(repository.count()).isZero();
	}

	@Test
	void anArticleForAnUnknownSourceIsRejectedByTheDatabase() {
		NewArticle orphan = new NewArticle(UUID.randomUUID(), "https://e.com/a", HASH_A, "t", null, PUBLISHED, "", "");

		assertThatThrownBy(() -> store.store(orphan)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingASourceDeletesItsArticles() {
		store.store(article("https://example.com/a", HASH_A));
		store.store(article("https://example.com/b", HASH_B));
		assertThat(repository.count()).isEqualTo(2);

		jdbc.update("delete from sources where id = ?", sourceId);

		assertThat(repository.count()).isZero();
	}

	@Test
	void theDatabaseEnforcesItsOwnLimitsAndVocabularies() {
		store.store(article("https://example.com/a", HASH_A));
		UUID id = repository.findAll().get(0).getId();

		assertThatThrownBy(() -> jdbc.update("update articles set status = 'DONE' where id = ?", id))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update articles set category = 'SPORTS' where id = ?", id))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update articles set enrichment_attempts = -1 where id = ?", id))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update articles set title = ? where id = ?", "t".repeat(301), id))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update articles set content_sanitized = ? where id = ?", "c".repeat(50_001), id))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
