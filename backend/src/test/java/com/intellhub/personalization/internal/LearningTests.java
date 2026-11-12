package com.intellhub.personalization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.personalization.ReadingState;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/** Clicks and saves change a user's learned tag weights, and only that user's. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LearningTests {

	private final ReadingState state;

	private final JdbcTemplate jdbc;

	private UUID source;

	private UUID alice;

	private UUID bob;

	private UUID rust;

	private UUID sqlite;

	LearningTests(ReadingState state, JdbcTemplate jdbc) {
		this.state = state;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from sources");
		jdbc.update("delete from users");
		jdbc.update("delete from tags");
		source = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", source,
				"https://example.com/" + source);
		alice = user("alice");
		bob = user("bob");
		rust = tag("rust");
		sqlite = tag("sqlite");
	}

	private UUID user(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, email, password_hash, role, enabled, created_at) values (?, ?, 'x', 'USER', true, now())",
				id, name + "-" + id + "@example.com");
		return id;
	}

	private UUID tag(String name) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into tags (id, name, kind, created_at) values (?, ?, 'TAXONOMY', now())", id, name);
		return id;
	}

	private UUID article(UUID... tags) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, published_at, ingested_at)
				values (?, ?, ?, ?, 't', now(), now())""", id, source, "https://example.com/a/" + id,
				String.format("%064x", Math.abs(id.getLeastSignificantBits())));
		for (UUID tag : tags) {
			jdbc.update("insert into article_tags (article_id, tag_id, origin) values (?, ?, 'LLM')", id, tag);
		}
		return id;
	}

	private Double learned(UUID user, UUID tag) {
		List<Double> rows = jdbc.queryForList("select learned_weight from user_tag_weights where user_id = ? and tag_id = ?",
				Double.class, user, tag);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private void age(UUID user, UUID tag, int days) {
		jdbc.update("update user_tag_weights set updated_at = now() - (? * interval '1 day') where user_id = ? and tag_id = ?", days, user,
				tag);
	}

	@Test
	void aClickAddsAStepToEveryTagOfTheArticle() {
		UUID article = article(rust, sqlite);

		state.recordClick(alice, article);

		assertThat(learned(alice, rust)).isCloseTo(Weights.CLICK_STEP, within(1e-9));
		assertThat(learned(alice, sqlite)).isCloseTo(Weights.CLICK_STEP, within(1e-9));
	}

	@Test
	void aSaveAddsALargerStepAndAnUnsaveTakesItBack() {
		UUID article = article(rust);

		state.save(alice, article);
		assertThat(learned(alice, rust)).isCloseTo(Weights.SAVE_STEP, within(1e-9));

		state.unsave(alice, article);
		assertThat(learned(alice, rust)).isNull(); // back to nothing: the empty row is removed
	}

	@Test
	void repeatedActionsThatChangeNothingAddNothing() {
		UUID article = article(rust);

		state.save(alice, article);
		state.save(alice, article); // already saved: no second event, no second step
		state.unsave(alice, article);
		state.unsave(alice, article);
		state.recordClick(alice, article);
		state.recordClick(alice, article); // within ten seconds: counts once

		assertThat(learned(alice, rust)).isCloseTo(Weights.CLICK_STEP, within(1e-9));
	}

	@Test
	void anUnsaveNeverGoesBelowZeroAndNeverTouchesAChosenWeight() {
		UUID article = article(rust);
		jdbc.update("insert into user_tag_weights values (?, ?, 0.7, 0.05, now())", alice, rust);

		state.save(alice, article); // 0.05 + 0.15
		jdbc.update("update user_tag_weights set learned_weight = 0.05 where user_id = ?", alice);
		state.unsave(alice, article); // 0.05 - 0.15 floors at 0

		assertThat(learned(alice, rust)).isZero();
		assertThat(jdbc.queryForObject("select explicit_weight from user_tag_weights where user_id = ? and tag_id = ?", Double.class,
				alice, rust)).isEqualTo(0.7);
	}

	@Test
	void theLearnedWeightStopsAtOne() {
		UUID article = article(rust);
		for (int i = 0; i < 10; i++) {
			state.save(alice, article);
			state.unsave(alice, article); // each pair nets to zero...
		}
		jdbc.update("insert into user_tag_weights values (?, ?, 0, 0.95, now()) on conflict (user_id, tag_id) do update set learned_weight = 0.95",
				alice, rust);

		state.save(alice, article);

		assertThat(learned(alice, rust)).isEqualTo(1.0);
	}

	@Test
	void whatWasLearnedFadesBeforeTheNextStepIsAdded() {
		UUID article = article(rust);
		jdbc.update("insert into user_tag_weights values (?, ?, 0, 0.8, now() - interval '14 days')", alice, rust);

		state.save(alice, article);

		assertThat(learned(alice, rust)).isCloseTo(0.8 * 0.5 + Weights.SAVE_STEP, within(1e-3)); // faded 0.4, plus 0.15
		age(alice, rust, 0);
	}

	@Test
	void anArticleWithoutTagsTeachesNothing() {
		state.recordClick(alice, article());
		state.save(alice, article());

		assertThat(jdbc.queryForObject("select count(*) from user_tag_weights", Integer.class)).isZero();
	}

	@Test
	void whatOneUserDoesNeverChangesAnotherUsersWeights() {
		UUID article = article(rust);

		state.save(alice, article);
		state.recordClick(bob, article);

		assertThat(learned(alice, rust)).isCloseTo(Weights.SAVE_STEP, within(1e-9));
		assertThat(learned(bob, rust)).isCloseTo(Weights.CLICK_STEP, within(1e-9));
	}

	@Test
	void manyParallelSavesOfDifferentArticlesAddUpWithoutLosingAnyStep() throws Exception {
		List<UUID> articles = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			articles.add(article(rust, sqlite));
		}
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (UUID article : articles) {
			futures.add(pool.submit(() -> {
				go.await();
				state.save(alice, article);
				return null;
			}));
		}
		go.countDown();
		for (Future<?> f : futures) {
			f.get();
		}
		pool.shutdown();

		assertThat(learned(alice, rust)).isCloseTo(4 * Weights.SAVE_STEP, within(1e-6));
		assertThat(learned(alice, sqlite)).isCloseTo(4 * Weights.SAVE_STEP, within(1e-6));
	}

}
