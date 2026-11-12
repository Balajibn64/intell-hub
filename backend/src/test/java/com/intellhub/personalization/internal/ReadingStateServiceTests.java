package com.intellhub.personalization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.personalization.ReadingState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
class ReadingStateServiceTests {

	private final ReadingState state;

	private final JdbcTemplate jdbc;

	private UUID sourceId;

	private UUID alice;

	private UUID bob;

	ReadingStateServiceTests(ReadingState state, JdbcTemplate jdbc) {
		this.state = state;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from users");
		sourceId = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", sourceId,
				"https://example.com/" + sourceId);
		alice = user("alice");
		bob = user("bob");
	}

	private UUID user(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'USER', true, now())",
				id, name + "-" + id + "@example.com");
		return id;
	}

	private UUID article() {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, published_at, ingested_at)
				values (?, ?, ?, ?, 't', now(), now())""", id, sourceId, "https://example.com/a/" + id,
				String.format("%064x", Math.abs(id.getLeastSignificantBits())));
		return id;
	}

	private int events(UUID user, UUID article, String type) {
		return jdbc.queryForObject("select count(*) from article_events where user_id = ? and article_id = ? and type = ?",
				Integer.class, user, article, type);
	}

	private int saved() {
		return jdbc.queryForObject("select count(*) from saved_articles", Integer.class);
	}

	// ---- save and unsave ----

	@Test
	void savingTwiceStoresOnceAndRecordsOneEvent() {
		UUID article = article();

		assertThat(state.save(alice, article)).isTrue();
		assertThat(state.save(alice, article)).isFalse();

		assertThat(saved()).isEqualTo(1);
		assertThat(events(alice, article, "SAVE")).isEqualTo(1);
	}

	@Test
	void unsavingRecordsAnEventOnlyWhenSomethingChanged() {
		UUID article = article();

		assertThat(state.unsave(alice, article)).isFalse();
		assertThat(events(alice, article, "UNSAVE")).isZero();

		state.save(alice, article);
		assertThat(state.unsave(alice, article)).isTrue();
		assertThat(state.unsave(alice, article)).isFalse();

		assertThat(saved()).isZero();
		assertThat(events(alice, article, "UNSAVE")).isEqualTo(1);
	}

	@Test
	void savingAgainAfterUnsavingIsANewSave() {
		UUID article = article();
		state.save(alice, article);
		state.unsave(alice, article);

		assertThat(state.save(alice, article)).isTrue();

		assertThat(events(alice, article, "SAVE")).isEqualTo(2);
		assertThat(saved()).isEqualTo(1);
	}

	@Test
	void concurrentSavesOfTheSameArticleStoreOneAndReportOneNewSave() throws Exception {
		UUID article = article();
		int threads = 8;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return state.save(alice, article);
				}));
			}
			ready.await();
			go.countDown();
			int newSaves = 0;
			for (Future<Boolean> future : futures) {
				if (future.get()) {
					newSaves++;
				}
			}
			assertThat(newSaves).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(saved()).isEqualTo(1);
		assertThat(events(alice, article, "SAVE")).isEqualTo(1);
	}

	// ---- clicks ----

	@Test
	void rapidRepeatClicksOnOneArticleCountOnce() {
		UUID article = article();

		state.recordClick(alice, article);
		state.recordClick(alice, article);
		state.recordClick(alice, article);

		assertThat(events(alice, article, "CLICK")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from article_events where created_at is null", Integer.class)).isZero();
	}

	@Test
	void aClickAfterTheDedupeWindowIsRecordedAgain() {
		UUID article = article();
		state.recordClick(alice, article);
		jdbc.update("update article_events set created_at = now() - interval '11 seconds' where article_id = ?", article);

		state.recordClick(alice, article);

		assertThat(events(alice, article, "CLICK")).isEqualTo(2);
	}

	@Test
	void theClickWindowIsPerUserAndPerArticle() {
		UUID first = article();
		UUID second = article();

		state.recordClick(alice, first);
		state.recordClick(bob, first);
		state.recordClick(alice, second);

		assertThat(events(alice, first, "CLICK")).isEqualTo(1);
		assertThat(events(bob, first, "CLICK")).isEqualTo(1);
		assertThat(events(alice, second, "CLICK")).isEqualTo(1);
	}

	// ---- per user ----

	@Test
	void usersSaveIndependentlyAndSeeOnlyTheirOwn() {
		UUID shared = article();
		UUID onlyAlice = article();

		state.save(alice, shared);
		state.save(alice, onlyAlice);
		state.save(bob, shared);

		assertThat(state.savedArticleIds(alice)).containsExactlyInAnyOrder(shared, onlyAlice);
		assertThat(state.savedArticleIds(bob)).containsExactly(shared);
		assertThat(state.savedAmong(bob, List.of(shared, onlyAlice))).containsExactly(shared);
		assertThat(state.unsave(bob, shared)).isTrue();
		assertThat(state.savedArticleIds(alice)).contains(shared);
	}

	@Test
	void savedArticlesAreListedNewestFirst() {
		UUID first = article();
		UUID second = article();
		UUID third = article();
		state.save(alice, first);
		state.save(alice, second);
		state.save(alice, third);
		jdbc.update("update saved_articles set saved_at = ? where article_id = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), first);
		jdbc.update("update saved_articles set saved_at = ? where article_id = ?", Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")), second);
		jdbc.update("update saved_articles set saved_at = ? where article_id = ?", Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")), third);

		assertThat(state.savedArticleIds(alice)).containsExactly(second, third, first);
	}

	@Test
	void theListOfSavedArticlesIsCapped() {
		for (int i = 0; i < ReadingState.MAX_SAVED_LISTED + 5; i++) {
			UUID article = article();
			jdbc.update("insert into saved_articles (user_id, article_id, saved_at) values (?, ?, now())", alice, article);
		}

		assertThat(state.savedArticleIds(alice)).hasSize(ReadingState.MAX_SAVED_LISTED);
	}

	@Test
	void savedAmongHandlesEmptyUnknownAndMixedInput() {
		UUID a = article();
		UUID b = article();
		state.save(alice, a);

		assertThat(state.savedAmong(alice, List.of())).isEmpty();
		assertThat(state.savedAmong(alice, null)).isEmpty();
		assertThat(state.savedAmong(alice, List.of(UUID.randomUUID()))).isEmpty();
		assertThat(state.savedAmong(alice, List.of(a, b, UUID.randomUUID()))).isEqualTo(Set.of(a));
	}

	// ---- integrity ----

	@Test
	void unknownArticlesAndUsersAreRejectedByTheDatabase() {
		UUID article = article();

		assertThatThrownBy(() -> state.save(alice, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> state.save(UUID.randomUUID(), article)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> state.recordClick(alice, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(saved()).isZero();
	}

	@Test
	void aFailedSaveLeavesNoEventBehind() {
		assertThatThrownBy(() -> state.save(alice, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

		assertThat(jdbc.queryForObject("select count(*) from article_events", Integer.class)).isZero();
	}

	@Test
	void deletingAnArticleOrAUserRemovesTheirReadingState() {
		UUID gone = article();
		UUID kept = article();
		state.save(alice, gone);
		state.recordClick(alice, gone);
		state.save(bob, kept);
		state.recordClick(bob, kept);

		jdbc.update("delete from articles where id = ?", gone);
		assertThat(saved()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from article_events where article_id = ?", Integer.class, gone)).isZero();

		jdbc.update("delete from users where id = ?", bob);
		assertThat(saved()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from article_events", Integer.class)).isZero();
	}

	@Test
	void theDatabaseOnlyAcceptsKnownEventTypes() {
		UUID article = article();

		assertThatThrownBy(() -> jdbc.update(
				"insert into article_events (id, user_id, article_id, type, created_at) values (?, ?, ?, 'VIEW', now())",
				UUID.randomUUID(), alice, article)).isInstanceOf(DataIntegrityViolationException.class);
	}

}
