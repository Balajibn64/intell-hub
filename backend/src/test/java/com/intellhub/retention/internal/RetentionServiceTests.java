package com.intellhub.retention.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.ArticleRetention;
import com.intellhub.identity.TokenMaintenance;
import com.intellhub.personalization.ReadingState;
import com.intellhub.retention.RetentionResult;
import com.intellhub.retention.RetentionRunningException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RetentionServiceTests {

	private final ArticleRetention articles;

	private final ReadingState state;

	private final TokenMaintenance tokens;

	private final Clock clock;

	private final PlatformTransactionManager transactionManager;

	private final JdbcTemplate jdbc;

	private UUID source;

	private UUID alice;

	private UUID bob;

	private int counter;

	RetentionServiceTests(ArticleRetention articles, ReadingState state, TokenMaintenance tokens, Clock clock,
			PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
		this.articles = articles;
		this.state = state;
		this.tokens = tokens;
		this.clock = clock;
		this.transactionManager = transactionManager;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from users");
		jdbc.update("delete from tags");
		counter = 0;
		source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source, "https://example.com/" + source);
		alice = user("alice");
		bob = user("bob");
	}

	private RetentionService service(int batchSize) {
		return new RetentionService(articles, state, tokens,
				new RetentionProperties(false, "0 30 3 * * *", Duration.ofDays(7), batchSize, Duration.ofDays(1)), clock, transactionManager);
	}

	private RetentionService service() {
		return service(500);
	}

	private UUID user(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'USER', true, now())", id,
				name + "-" + id + "@example.com");
		return id;
	}

	private UUID article(Duration age) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, published_at, ingested_at)
				values (?, ?, ?, ?, 't', ?, now())""", id, source, "https://example.com/a/" + id, String.format("%064x", ++counter * 7919L),
				Timestamp.from(clock.instant().minus(age)));
		return id;
	}

	private UUID old() {
		return article(Duration.ofDays(30));
	}

	private boolean exists(UUID id) {
		return jdbc.queryForObject("select count(*) from articles where id = ?", Integer.class, id) == 1;
	}

	private int articleCount() {
		return jdbc.queryForObject("select count(*) from articles", Integer.class);
	}

	@Test
	void oldUnsavedArticlesAreDeletedAndRecentOnesAreKept() {
		UUID oldOne = old();
		UUID recent = article(Duration.ofDays(2));
		UUID almost = article(Duration.ofDays(7).minusMinutes(5));

		RetentionResult result = service().run(false);

		assertThat(exists(oldOne)).isFalse();
		assertThat(exists(recent)).isTrue();
		assertThat(exists(almost)).isTrue();
		assertThat(result.articlesExpired()).isEqualTo(1);
		assertThat(result.articlesDeleted()).isEqualTo(1);
		assertThat(result.articlesKeptBecauseSaved()).isZero();
		assertThat(result.dryRun()).isFalse();
	}

	@Test
	void anArticleJustPastTheWindowIsDeleted() {
		UUID justPast = article(Duration.ofDays(7).plusMinutes(5));

		service().run(false);

		assertThat(exists(justPast)).isFalse();
	}

	@Test
	void anArticleAnyUserHasSavedIsKeptNoMatterHowOld() {
		UUID byAlice = old();
		UUID byBob = old();
		UUID byBoth = old();
		UUID unsaved = old();
		state.save(alice, byAlice);
		state.save(bob, byBob);
		state.save(alice, byBoth);
		state.save(bob, byBoth);

		RetentionResult result = service().run(false);

		assertThat(exists(byAlice)).isTrue();
		assertThat(exists(byBob)).isTrue();
		assertThat(exists(byBoth)).isTrue();
		assertThat(exists(unsaved)).isFalse();
		assertThat(result.articlesExpired()).isEqualTo(4);
		assertThat(result.articlesKeptBecauseSaved()).isEqualTo(3);
		assertThat(result.articlesDeleted()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from saved_articles", Integer.class)).isEqualTo(4);
	}

	@Test
	void unsavingMakesAnArticlePurgeableAtTheNextRun() {
		UUID article = old();
		state.save(alice, article);
		service().run(false);
		assertThat(exists(article)).isTrue();

		state.unsave(alice, article);
		service().run(false);

		assertThat(exists(article)).isFalse();
	}

	@Test
	void deletedArticlesTakeTheirTagLinksAndEventsButNothingElseIsTouched() {
		UUID gone = old();
		UUID tag = UUID.randomUUID();
		jdbc.update("insert into tags (id, name, kind, created_at) values (?, 'rust', 'TAXONOMY', now())", tag);
		jdbc.update("insert into article_tags (article_id, tag_id, origin) values (?, ?, 'LLM')", gone, tag);
		state.recordClick(alice, gone);
		// the click on a tagged article already taught Alice a weight for that tag
		assertThat(jdbc.queryForObject("select count(*) from user_tag_weights", Integer.class)).isEqualTo(1);
		jdbc.update("insert into llm_usage (day, provider, calls, failures) values (current_date, 'gemini', 3, 0)");

		service().run(false);

		assertThat(articleCount()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from article_tags", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from article_events", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from user_tag_weights", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from llm_usage", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from sources", Integer.class)).isEqualTo(1);
	}

	@Test
	void everyExpiredArticleIsReachedWhenTheBatchesAreSmallerThanTheDataAndSavedOnesAreSpreadThrough() {
		List<UUID> unsaved = new ArrayList<>();
		List<UUID> saved = new ArrayList<>();
		for (int i = 0; i < 37; i++) {
			UUID id = old();
			if (i % 4 == 0) {
				state.save(alice, id);
				saved.add(id);
			}
			else {
				unsaved.add(id);
			}
		}
		UUID recent = article(Duration.ofDays(1));

		RetentionResult result = service(5).run(false);

		assertThat(result.articlesExpired()).isEqualTo(37);
		assertThat(result.articlesDeleted()).isEqualTo(unsaved.size());
		assertThat(result.articlesKeptBecauseSaved()).isEqualTo(saved.size());
		unsaved.forEach(id -> assertThat(exists(id)).isFalse());
		saved.forEach(id -> assertThat(exists(id)).isTrue());
		assertThat(exists(recent)).isTrue();
	}

	@Test
	void aBatchSizeThatExactlyDividesTheDataStillStopsAndAnEmptyTableIsFine() {
		for (int i = 0; i < 10; i++) {
			old();
		}

		assertThat(service(5).run(false).articlesDeleted()).isEqualTo(10);
		assertThat(service(5).run(false).articlesExpired()).isZero();
	}

	@Test
	void runningAgainDeletesNothingMore() {
		old();
		old();
		service().run(false);

		RetentionResult second = service().run(false);

		assertThat(second.articlesExpired()).isZero();
		assertThat(second.articlesDeleted()).isZero();
	}

	@Test
	void aDryRunChangesNothingButReportsWhatARealRunWouldDo() {
		UUID savedOld = old();
		UUID unsavedOld = old();
		state.save(alice, savedOld);
		jdbc.update("""
				insert into refresh_tokens (id, user_id, family_id, token_hash, expires_at, created_at)
				values (?, ?, ?, 'hash-dry-run', now() - interval '10 days', now())""", UUID.randomUUID(), alice, UUID.randomUUID());

		RetentionResult dry = service().run(true);

		assertThat(dry.dryRun()).isTrue();
		assertThat(dry.articlesExpired()).isEqualTo(2);
		assertThat(dry.articlesKeptBecauseSaved()).isEqualTo(1);
		assertThat(dry.articlesDeleted()).isZero();
		assertThat(dry.tokensDeleted()).isZero();
		assertThat(exists(unsavedOld)).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(1);
	}

	@Test
	void expiredLoginTokensAreDeletedAfterTheGraceAndActiveOnesAreKept() {
		jdbc.update("""
				insert into refresh_tokens (id, user_id, family_id, token_hash, expires_at, created_at) values
				(?, ?, ?, 'hash-expired-long', now() - interval '3 days', now()),
				(?, ?, ?, 'hash-expired-today', now() - interval '2 hours', now()),
				(?, ?, ?, 'hash-active', now() + interval '3 days', now())""", UUID.randomUUID(), alice, UUID.randomUUID(), UUID.randomUUID(), alice,
				UUID.randomUUID(), UUID.randomUUID(), alice, UUID.randomUUID());

		RetentionResult result = service().run(false);

		assertThat(result.tokensDeleted()).isEqualTo(1); // only the one expired for longer than the one-day grace
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(2);
	}

	@Test
	void anArticleBeingEnrichedOrFailedIsPurgedLikeAnyOtherWhenOld() {
		UUID enriching = old();
		jdbc.update("update articles set status = 'ENRICHING', claimed_at = now() where id = ?", enriching);

		service().run(false);

		assertThat(exists(enriching)).isFalse();
	}

	@Test
	void theLastRunIsRememberedForTheStatusPage() {
		RetentionService service = service();
		assertThat(service.lastRun()).isEmpty();
		old();

		RetentionResult result = service.run(false);

		assertThat(service.lastRun()).contains(result);
	}

	@Test
	void twoRunsAtOnceAreRefusedNotInterleaved() throws Exception {
		for (int i = 0; i < 300; i++) {
			old();
		}
		RetentionService service = service(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger refused = new AtomicInteger();
		List<Future<RetentionResult>> futures = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			futures.add(pool.submit(() -> {
				go.await();
				try {
					return service.run(false);
				}
				catch (RetentionRunningException e) {
					refused.incrementAndGet();
					return null;
				}
			}));
		}
		go.countDown();
		int completed = 0;
		for (Future<RetentionResult> f : futures) {
			if (f.get(60, TimeUnit.SECONDS) != null) {
				completed++;
			}
		}
		pool.shutdown();

		assertThat(completed + refused.get()).isEqualTo(2);
		assertThat(completed).isGreaterThanOrEqualTo(1);
		assertThat(articleCount()).isZero();
	}

	@Test
	void savesRunningDuringAPurgeNeverEndInAHalfStateAndNeverLoseAKeptSave() throws Exception {
		List<UUID> candidates = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			candidates.add(old());
		}
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch go = new CountDownLatch(1);
		List<UUID> savedOk = java.util.Collections.synchronizedList(new ArrayList<>());
		List<Future<?>> savers = new ArrayList<>();
		for (int w = 0; w < 3; w++) {
			final int worker = w;
			savers.add(pool.submit(() -> {
				go.await();
				for (int i = worker; i < candidates.size(); i += 3) {
					try {
						state.save(i % 2 == 0 ? alice : bob, candidates.get(i));
						savedOk.add(candidates.get(i));
					}
					catch (DataIntegrityViolationException e) {
						// the article was purged first: the save finds it gone, as for any missing article
					}
				}
				return null;
			}));
		}
		Future<RetentionResult> purge = pool.submit(() -> {
			go.await();
			return service(7).run(false);
		});
		go.countDown();
		for (Future<?> f : savers) {
			f.get(60, TimeUnit.SECONDS);
		}
		purge.get(60, TimeUnit.SECONDS);
		pool.shutdown();

		// Every save that succeeded is still there, with its article.
		for (UUID id : savedOk) {
			assertThat(exists(id)).as("an article whose save succeeded must not be purged").isTrue();
		}
		// No save row points at a missing article (the foreign key guarantees it) and every article left is saved.
		assertThat(jdbc.queryForObject("select count(*) from saved_articles s where not exists (select 1 from articles a where a.id = s.article_id)",
				Integer.class)).isZero();
		service().run(false); // a second run now removes everything not saved
		assertThat(articleCount()).isEqualTo((int) jdbc.queryForObject("select count(distinct article_id) from saved_articles", Integer.class));
	}

	@Test
	void aRunWithNothingToDoIsAllZerosAndHasATimestamp() {
		RetentionResult result = service().run(false);

		assertThat(result.articlesExpired()).isZero();
		assertThat(result.articlesDeleted()).isZero();
		assertThat(result.tokensDeleted()).isZero();
		assertThat(result.finishedAt()).isNotNull();
		assertThatThrownBy(() -> new RetentionService(articles, state, tokens, null, clock, transactionManager).run(false))
				.isInstanceOf(NullPointerException.class); // settings are required, there is no silent default window
	}

}
