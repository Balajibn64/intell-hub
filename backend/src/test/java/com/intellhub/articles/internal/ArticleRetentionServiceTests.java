package com.intellhub.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.ArticleRetention;
import com.intellhub.articles.ArticleStatistics;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
class ArticleRetentionServiceTests {

	private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

	private final ArticleRetention retention;

	private final ArticleStatistics statistics;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate tx;

	private UUID source;

	private UUID user;

	private int counter;

	ArticleRetentionServiceTests(ArticleRetention retention, ArticleStatistics statistics, JdbcTemplate jdbc, TransactionTemplate tx) {
		this.retention = retention;
		this.statistics = statistics;
		this.jdbc = jdbc;
		this.tx = tx;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from users");
		jdbc.update("delete from tags");
		counter = 0;
		source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source, "https://example.com/" + source);
		user = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'USER', true, now())", user,
				"u-" + user + "@example.com");
	}

	private UUID article(Instant published, String status) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, published_at, ingested_at, status)
				values (?, ?, ?, ?, 't', ?, now(), ?)""", id, source, "https://example.com/a/" + id, String.format("%064x", ++counter * 7919L),
				Timestamp.from(published), status);
		return id;
	}

	private UUID oldArticle() {
		return article(NOW.minus(Duration.ofDays(30)), "PENDING");
	}

	private List<UUID> lock(Instant cutoff, UUID after, int limit) {
		return tx.execute(s -> retention.lockExpired(cutoff, after, limit));
	}

	private int del(java.util.Collection<UUID> ids) {
		Integer deleted = tx.execute(s -> retention.delete(ids));
		return deleted;
	}

	private int articles() {
		return jdbc.queryForObject("select count(*) from articles", Integer.class);
	}

	@Test
	void expiredArticlesAreSelectedInIdOrderAndNewerOnesAreNot() {
		List<UUID> old = List.of(oldArticle(), oldArticle(), oldArticle());
		article(NOW.minus(Duration.ofDays(1)), "PENDING");

		List<UUID> locked = lock(NOW.minus(Duration.ofDays(7)), null, 100);

		assertThat(locked).containsExactlyInAnyOrderElementsOf(old); // PostgreSQL orders uuids bytewise, which is not Java's UUID order
	}

	@Test
	void theCutoffIsExclusive() {
		Instant cutoff = NOW.minus(Duration.ofDays(7));
		UUID exactly = article(cutoff, "PENDING");
		UUID justBefore = article(cutoff.minusSeconds(1), "PENDING");

		assertThat(lock(cutoff, null, 100)).containsExactly(justBefore).doesNotContain(exactly);
	}

	@Test
	void aCursorWalksThroughEveryExpiredArticleInBatches() {
		List<UUID> old = new ArrayList<>();
		for (int i = 0; i < 23; i++) {
			old.add(oldArticle());
		}
		List<UUID> seen = new ArrayList<>();
		UUID after = null;
		while (true) {
			UUID cursor = after;
			List<UUID> batch = lock(NOW, cursor, 5);
			if (batch.isEmpty()) {
				break;
			}
			seen.addAll(batch);
			after = batch.get(batch.size() - 1);
		}

		assertThat(seen).containsExactlyInAnyOrderElementsOf(old).doesNotHaveDuplicates();
	}

	@Test
	void deleteRemovesTheArticlesWithTheirTagLinksAndEventsAndNothingElse() {
		UUID gone = oldArticle();
		UUID kept = oldArticle();
		UUID tag = UUID.randomUUID();
		jdbc.update("insert into tags (id, name, kind, created_at) values (?, 'rust', 'TAXONOMY', now())", tag);
		jdbc.update("insert into article_tags (article_id, tag_id, origin) values (?, ?, 'LLM')", gone, tag);
		jdbc.update("insert into article_tags (article_id, tag_id, origin) values (?, ?, 'LLM')", kept, tag);
		jdbc.update("insert into article_events (id, user_id, article_id, type, created_at) values (?, ?, ?, 'CLICK', now())", UUID.randomUUID(), user, gone);

		int deleted = del(List.of(gone));

		assertThat(deleted).isEqualTo(1);
		assertThat(articles()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from article_tags", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from article_events", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from sources", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
	}

	@Test
	void deletingNothingOrUnknownIdsDoesNothing() {
		oldArticle();

		assertThat(del(List.of())).isZero();
		assertThat(del(null)).isZero();
		assertThat(del(List.of(UUID.randomUUID()))).isZero();
		assertThat(articles()).isEqualTo(1);
	}

	@Test
	void bothMethodsRefuseToRunWithoutATransaction() {
		assertThatThrownBy(() -> retention.lockExpired(NOW, null, 10)).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> retention.delete(List.of(UUID.randomUUID()))).isInstanceOf(RuntimeException.class);
	}

	@Test
	void badBatchSizesAreRefused() {
		for (int bad : new int[] { 0, -1, 5001 }) {
			assertThatThrownBy(() -> lock(NOW, null, bad)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> lock(null, null, 10)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void lockedRowsMakeAConcurrentSaveWaitForTheTransaction() throws Exception {
		UUID id = oldArticle();
		var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
		java.util.concurrent.Future<?> save;
		try {
			save = tx.execute(s -> {
				retention.lockExpired(NOW, null, 10);
				java.util.concurrent.Future<?> f = pool.submit(() -> jdbc.update(
						"insert into saved_articles (user_id, article_id, saved_at) values (?, ?, now())", user, id));
				try {
					Thread.sleep(500);
					assertThat(f.isDone()).as("the save must wait for the lock").isFalse();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return f;
			});
			// The transaction has ended, so the waiting save can now finish.
			save.get(10, java.util.concurrent.TimeUnit.SECONDS);
		}
		finally {
			pool.shutdown();
		}
		assertThat(jdbc.queryForObject("select count(*) from saved_articles", Integer.class)).isEqualTo(1);
	}

	@Test
	void statisticsCountByStatusIncludingZerosAndReportTheOldestArticle() {
		article(NOW.minus(Duration.ofDays(3)), "ENRICHED");
		article(NOW.minus(Duration.ofDays(9)), "ENRICHED");
		article(NOW.minus(Duration.ofDays(1)), "PENDING");

		assertThat(statistics.total()).isEqualTo(3);
		assertThat(statistics.countsByStatus()).containsEntry("ENRICHED", 2L).containsEntry("PENDING", 1L).containsEntry("FAILED", 0L)
				.containsEntry("EXCERPT_ONLY", 0L).containsEntry("ENRICHING", 0L).hasSize(5);
		assertThat(statistics.oldestPublishedAt()).contains(NOW.minus(Duration.ofDays(9)));
	}

	@Test
	void statisticsOnAnEmptyTableAreZeroAndEmpty() {
		assertThat(statistics.total()).isZero();
		assertThat(statistics.oldestPublishedAt()).isEmpty();
		assertThat(statistics.countsByStatus().values()).containsOnly(0L);
	}

}
