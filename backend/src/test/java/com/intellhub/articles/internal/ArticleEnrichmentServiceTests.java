package com.intellhub.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.AppliedEnrichment;
import com.intellhub.articles.ArticleEnrichment;
import com.intellhub.articles.ClaimKind;
import com.intellhub.articles.EnrichmentRequests;
import com.intellhub.articles.EnrichmentInput;
import com.intellhub.articles.TriageCandidate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
class ArticleEnrichmentServiceTests {

	private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

	private final ArticleEnrichment enrichment;

	private final EnrichmentRequests requests;

	private final JdbcTemplate jdbc;

	private UUID source;

	private int counter;

	ArticleEnrichmentServiceTests(ArticleEnrichment enrichment, EnrichmentRequests requests, JdbcTemplate jdbc) {
		this.enrichment = enrichment;
		this.requests = requests;
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

	private UUID article(String status, Double score, int secondsAfterBase) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into articles (id, source_id, canonical_url, content_hash, title, excerpt, published_at, ingested_at,
				status, heuristic_score)
				values (?, ?, ?, ?, ?, ?, ?, now(), ?, ?)""", id, source, "https://example.com/a/" + id,
				String.format("%064x", 9_000L + (++counter)), "Title " + counter, "Excerpt " + counter,
				Timestamp.from(BASE.plusSeconds(secondsAfterBase)), status, score);
		return id;
	}

	private String status(UUID id) {
		return jdbc.queryForObject("select status from articles where id = ?", String.class, id);
	}

	private int attempts(UUID id) {
		return jdbc.queryForObject("select enrichment_attempts from articles where id = ?", Integer.class, id);
	}

	private List<String> tagsOf(UUID id) {
		return jdbc.queryForList("select t.name from article_tags at join tags t on t.id = at.tag_id where at.article_id = ? order by t.name",
				String.class, id);
	}

	private static AppliedEnrichment applied(UUID id, String category, List<String> tags) {
		return new AppliedEnrichment(id, "A summary.", category, 0.8, tags, "LLM", "fake");
	}

	// ---- triage ----

	@Test
	void triageCandidatesAreUnscoredPendingArticlesNewestFirst() {
		UUID older = article("PENDING", null, 1);
		UUID newer = article("PENDING", null, 2);
		article("PENDING", 0.5, 3); // already scored
		article("ENRICHED", null, 4);
		article("EXCERPT_ONLY", null, 5);

		List<TriageCandidate> candidates = enrichment.triageCandidates(10);

		assertThat(candidates).extracting(TriageCandidate::id).containsExactly(newer, older);
		assertThat(candidates.get(0).title()).startsWith("Title");
		assertThat(enrichment.triageCandidates(1)).hasSize(1);
	}

	@Test
	void recordTriageScoresAndSkipsLowValueArticles() {
		UUID worth = article("PENDING", null, 1);
		UUID skip = article("PENDING", null, 2);

		enrichment.recordTriage(worth, 0.7, true);
		enrichment.recordTriage(skip, 0.01, false);

		assertThat(status(worth)).isEqualTo("PENDING");
		assertThat(status(skip)).isEqualTo("EXCERPT_ONLY");
		assertThat(jdbc.queryForObject("select heuristic_score from articles where id = ?", Double.class, worth)).isEqualTo(0.7);
		assertThat(enrichment.triageCandidates(10)).isEmpty();
	}

	@Test
	void recordTriageOnlyAppliesToPendingUnscoredArticles() {
		UUID enriched = article("ENRICHED", null, 1);
		UUID scored = article("PENDING", 0.4, 2);

		enrichment.recordTriage(enriched, 0.9, false);
		enrichment.recordTriage(scored, 0.9, false);

		assertThat(status(enriched)).isEqualTo("ENRICHED");
		assertThat(status(scored)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select heuristic_score from articles where id = ?", Double.class, scored)).isEqualTo(0.4);
	}

	// ---- claiming ----

	@Test
	void claimingMarksArticlesEnrichingCountsTheAttemptAndReturnsPlainTextInput() {
		UUID id = article("PENDING", 0.5, 1);

		List<EnrichmentInput> batch = enrichment.claimBatch(5, ClaimKind.BACKGROUND);

		assertThat(batch).hasSize(1);
		EnrichmentInput input = batch.get(0);
		assertThat(input.id()).isEqualTo(id);
		assertThat(input.title()).isEqualTo("Title 1");
		assertThat(input.excerpt()).isEqualTo("Excerpt 1");
		assertThat(input.attempts()).isEqualTo(1);
		assertThat(input.requested()).isFalse();
		assertThat(status(id)).isEqualTo("ENRICHING");
		assertThat(jdbc.queryForObject("select claimed_at is not null from articles where id = ?", Boolean.class, id)).isTrue();
	}

	@Test
	void claimingOnlyTakesScoredPendingArticlesAndRespectsTheSize() {
		article("PENDING", null, 1); // not triaged yet
		article("ENRICHED", 0.9, 2);
		article("ENRICHING", 0.9, 3);
		article("EXCERPT_ONLY", 0.1, 4);
		article("FAILED", 0.9, 5);
		UUID a = article("PENDING", 0.9, 6);
		UUID b = article("PENDING", 0.8, 7);
		UUID c = article("PENDING", 0.7, 8);

		List<EnrichmentInput> batch = enrichment.claimBatch(2, ClaimKind.BACKGROUND);

		assertThat(batch).extracting(EnrichmentInput::id).containsExactlyInAnyOrder(a, b);
		assertThat(status(c)).isEqualTo("PENDING");
		assertThat(enrichment.claimBatch(0, ClaimKind.BACKGROUND)).isEmpty();
		assertThat(enrichment.claimBatch(-3, ClaimKind.BACKGROUND)).isEmpty();
	}

	@Test
	void backgroundClaimsGoByHighestScoreThenNewestAndNeverTakeReadersRequests() {
		UUID lowScore = article("PENDING", 0.1, 10);
		UUID highOld = article("PENDING", 0.9, 1);
		UUID highNew = article("PENDING", 0.9, 2);
		UUID requested = article("PENDING", 0.99, 3);
		jdbc.update("update articles set enrichment_requested_at = now() where id = ?", requested);

		List<EnrichmentInput> batch = enrichment.claimBatch(10, ClaimKind.BACKGROUND);

		assertThat(batch).extracting(EnrichmentInput::id).containsExactly(highNew, highOld, lowScore);
		assertThat(batch).noneMatch(EnrichmentInput::requested);
		assertThat(status(requested)).isEqualTo("PENDING");
	}

	@Test
	void requestedClaimsTakeOnlyReadersRequestsOldestFirst() {
		article("PENDING", 0.99, 1); // background work with a top score
		UUID second = article("PENDING", 0.01, 2);
		UUID first = article("PENDING", 0.05, 3);
		jdbc.update("update articles set enrichment_requested_at = now() - interval '2 minutes' where id = ?", first);
		jdbc.update("update articles set enrichment_requested_at = now() - interval '1 minute' where id = ?", second);

		List<EnrichmentInput> batch = enrichment.claimBatch(10, ClaimKind.REQUESTED);

		assertThat(batch).extracting(EnrichmentInput::id).containsExactly(first, second);
		assertThat(batch).allMatch(EnrichmentInput::requested);
		assertThat(enrichment.claimBatch(10, ClaimKind.REQUESTED)).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from articles where status = 'PENDING'", Integer.class)).isEqualTo(1);
	}

	@Test
	void anEmptyQueueGivesAnEmptyBatch() {
		assertThat(enrichment.claimBatch(5, ClaimKind.BACKGROUND)).isEmpty();
	}

	@Test
	void concurrentWorkersNeverClaimTheSameArticle() throws Exception {
		for (int i = 0; i < 24; i++) {
			article("PENDING", 0.5, i);
		}
		int workers = 8;
		CountDownLatch ready = new CountDownLatch(workers);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		try {
			List<Future<List<EnrichmentInput>>> futures = new ArrayList<>();
			for (int i = 0; i < workers; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return enrichment.claimBatch(3, ClaimKind.BACKGROUND);
				}));
			}
			ready.await();
			go.countDown();
			Set<UUID> seen = new HashSet<>();
			int total = 0;
			for (Future<List<EnrichmentInput>> future : futures) {
				for (EnrichmentInput input : future.get()) {
					total++;
					assertThat(seen.add(input.id())).as("claimed twice: " + input.id()).isTrue();
				}
			}
			assertThat(total).isEqualTo(24);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("select count(*) from articles where status = 'ENRICHING'", Integer.class)).isEqualTo(24);
		assertThat(jdbc.queryForObject("select max(enrichment_attempts) from articles", Integer.class)).isEqualTo(1);
	}

	// ---- applying results ----

	@Test
	void applyingResultsFillsTheArticleAndLinksTags() {
		UUID id = article("PENDING", 0.5, 1);
		enrichment.upsertTaxonomyTags(List.of("kubernetes"));
		enrichment.claimBatch(1, ClaimKind.BACKGROUND);

		enrichment.applyResults(List.of(applied(id, "SOFTWARE_ENGINEERING", List.of("kubernetes", "operators"))), 100);

		assertThat(status(id)).isEqualTo("ENRICHED");
		assertThat(jdbc.queryForObject("select summary from articles where id = ?", String.class, id)).isEqualTo("A summary.");
		assertThat(jdbc.queryForObject("select category from articles where id = ?", String.class, id)).isEqualTo("SOFTWARE_ENGINEERING");
		assertThat(jdbc.queryForObject("select relevance_score from articles where id = ?", Double.class, id)).isEqualTo(0.8);
		assertThat(jdbc.queryForObject("select enriched_by from articles where id = ?", String.class, id)).isEqualTo("fake");
		assertThat(jdbc.queryForObject("select enriched_at is not null from articles where id = ?", Boolean.class, id)).isTrue();
		assertThat(tagsOf(id)).containsExactly("kubernetes", "operators");
		assertThat(jdbc.queryForObject("select kind from tags where name = 'kubernetes'", String.class)).isEqualTo("TAXONOMY");
		assertThat(jdbc.queryForObject("select kind from tags where name = 'operators'", String.class)).isEqualTo("FREE");
	}

	@Test
	void resultsForArticlesThatAreNotEnrichingAreIgnored() {
		UUID pending = article("PENDING", 0.5, 1);
		UUID failed = article("FAILED", 0.5, 2);
		UUID enriched = article("ENRICHED", 0.5, 3);
		jdbc.update("update articles set summary = 'original' where id = ?", enriched);

		enrichment.applyResults(List.of(applied(pending, "AI_ML", List.of("llm")), applied(failed, null, List.of("llm")),
				applied(enriched, "AI_ML", List.of("llm")), applied(UUID.randomUUID(), null, List.of())), 100);

		assertThat(status(pending)).isEqualTo("PENDING");
		assertThat(status(failed)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select summary from articles where id = ?", String.class, enriched)).isEqualTo("original");
		assertThat(jdbc.queryForObject("select count(*) from article_tags", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isZero();
	}

	@Test
	void applyingAgainReplacesTheModelsTagsButKeepsOtherOrigins() {
		UUID id = article("PENDING", 0.5, 1);
		enrichment.claimBatch(1, ClaimKind.BACKGROUND);
		enrichment.applyResults(List.of(applied(id, null, List.of("first", "second"))), 100);
		jdbc.update("update articles set status = 'ENRICHING' where id = ?", id);
		jdbc.update("""
				insert into tags (id, name, kind, created_at) values (gen_random_uuid(), 'heur', 'TAXONOMY', now())""");
		jdbc.update("insert into article_tags (article_id, tag_id, origin) select ?, id, 'HEURISTIC' from tags where name = 'heur'", id);

		enrichment.applyResults(List.of(applied(id, null, List.of("second", "third"))), 100);

		assertThat(tagsOf(id)).containsExactly("heur", "second", "third");
	}

	@Test
	void theSameTagIsStoredOnceAcrossArticles() {
		UUID a = article("PENDING", 0.5, 1);
		UUID b = article("PENDING", 0.5, 2);
		enrichment.claimBatch(2, ClaimKind.BACKGROUND);

		enrichment.applyResults(List.of(applied(a, null, List.of("rust")), applied(b, null, List.of("rust"))), 100);

		assertThat(jdbc.queryForObject("select count(*) from tags where name = 'rust'", Integer.class)).isEqualTo(1);
		assertThat(tagsOf(a)).containsExactly("rust");
		assertThat(tagsOf(b)).containsExactly("rust");
	}

	@Test
	void newFreeTagsStopWhenTheCapIsReachedButExistingTagsStillLink() {
		UUID a = article("PENDING", 0.5, 1);
		UUID b = article("PENDING", 0.5, 2);
		enrichment.claimBatch(2, ClaimKind.BACKGROUND);

		enrichment.applyResults(List.of(applied(a, null, List.of("one", "two", "three"))), 2);
		enrichment.applyResults(List.of(applied(b, null, List.of("two", "four"))), 2);

		assertThat(tagsOf(a)).containsExactly("one", "two");
		assertThat(tagsOf(b)).containsExactly("two");
		assertThat(jdbc.queryForObject("select count(*) from tags where kind = 'FREE'", Integer.class)).isEqualTo(2);
	}

	@Test
	void theDatabaseRejectsBadCategoriesAndBadTagNamesAndRollsEverythingBack() {
		UUID id = article("PENDING", 0.5, 1);
		enrichment.claimBatch(1, ClaimKind.BACKGROUND);

		assertThatThrownBy(() -> enrichment.applyResults(List.of(applied(id, "SPORTS", List.of("ok"))), 100))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(status(id)).isEqualTo("ENRICHING");
		assertThatThrownBy(() -> enrichment.applyResults(List.of(applied(id, null, List.of("Has Space"))), 100))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(status(id)).isEqualTo("ENRICHING");
		assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isZero();
	}

	// ---- release, fail, recover, request ----

	@Test
	void releaseAndFailOnlyMoveArticlesThatAreEnriching() {
		UUID claimed = article("PENDING", 0.5, 1);
		UUID other = article("PENDING", 0.5, 2);
		enrichment.claimBatch(1, ClaimKind.BACKGROUND);
		UUID enrichingId = jdbc.queryForObject("select id from articles where status = 'ENRICHING'", UUID.class);
		UUID pendingId = enrichingId.equals(claimed) ? other : claimed;

		enrichment.fail(List.of(pendingId));
		assertThat(status(pendingId)).isEqualTo("PENDING");
		enrichment.release(List.of(enrichingId));
		assertThat(status(enrichingId)).isEqualTo("PENDING");
		enrichment.claimBatch(5, ClaimKind.BACKGROUND);
		enrichment.fail(List.of(enrichingId));
		assertThat(status(enrichingId)).isEqualTo("FAILED");
		enrichment.release(List.of());
		enrichment.fail(null);
	}

	@Test
	void staleClaimsAreRecoveredAndRecentOnesAreLeftAlone() {
		UUID stale = article("PENDING", 0.5, 1);
		UUID recent = article("PENDING", 0.5, 2);
		enrichment.claimBatch(2, ClaimKind.BACKGROUND);
		jdbc.update("update articles set claimed_at = now() - interval '30 minutes' where id = ?", stale);
		jdbc.update("update articles set claimed_at = now() - interval '1 minute' where id = ?", recent);

		ArticleEnrichment.Recovered recovered = enrichment.recoverStale(Duration.ofMinutes(10), Duration.ofDays(1));

		assertThat(recovered.staleClaims()).isEqualTo(1);
		assertThat(status(stale)).isEqualTo("PENDING");
		assertThat(status(recent)).isEqualTo("ENRICHING");
	}

	@Test
	void failedArticlesAreRetriedAfterADayWithTheirAttemptsReset() {
		UUID old = article("PENDING", 0.5, 1);
		UUID fresh = article("PENDING", 0.5, 2);
		enrichment.claimBatch(2, ClaimKind.BACKGROUND);
		enrichment.fail(List.of(old, fresh));
		jdbc.update("update articles set claimed_at = now() - interval '25 hours' where id = ?", old);

		ArticleEnrichment.Recovered recovered = enrichment.recoverStale(Duration.ofMinutes(10), Duration.ofDays(1));

		assertThat(recovered.retriedFailures()).isEqualTo(1);
		assertThat(status(old)).isEqualTo("PENDING");
		assertThat(attempts(old)).isZero();
		assertThat(status(fresh)).isEqualTo("FAILED");
		assertThat(attempts(fresh)).isEqualTo(1);
	}

	@Test
	void requestingEnrichmentOnlyWorksForSkippedArticlesAndOnlyOnce() {
		UUID skipped = article("EXCERPT_ONLY", 0.1, 1);

		assertThat(requests.requestEnrichment(skipped)).isTrue();
		assertThat(status(skipped)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select enrichment_requested_at is not null from articles where id = ?", Boolean.class, skipped)).isTrue();
		assertThat(requests.requestEnrichment(skipped)).isFalse();

		for (String state : List.of("PENDING", "ENRICHING", "ENRICHED", "FAILED")) {
			UUID id = article(state, 0.5, 5);
			assertThat(requests.requestEnrichment(id)).as(state).isFalse();
			assertThat(status(id)).isEqualTo(state);
		}
		assertThat(requests.requestEnrichment(UUID.randomUUID())).isFalse();
	}

	@Test
	void aRequestedArticleIsServedByRequestedClaimsEvenWithALowScore() {
		article("PENDING", 0.99, 1);
		UUID skipped = article("EXCERPT_ONLY", 0.01, 2);
		requests.requestEnrichment(skipped);

		assertThat(enrichment.claimBatch(1, ClaimKind.REQUESTED)).extracting(EnrichmentInput::id).containsExactly(skipped);
	}

	@Test
	void queuedRequestsAreCappedSoOneScriptCannotFillTheQueue() {
		List<UUID> skipped = new ArrayList<>();
		for (int i = 0; i < EnrichmentRequests.MAX_QUEUED_REQUESTS + 5; i++) {
			skipped.add(article("EXCERPT_ONLY", 0.0, i));
		}

		int accepted = 0;
		for (UUID id : skipped) {
			if (requests.requestEnrichment(id)) {
				accepted++;
			}
		}

		assertThat(accepted).isEqualTo(EnrichmentRequests.MAX_QUEUED_REQUESTS);
		assertThat(jdbc.queryForObject("select count(*) from articles where status = 'EXCERPT_ONLY'", Integer.class)).isEqualTo(5);
		// Once one is served the queue has room again.
		UUID served = enrichment.claimBatch(1, ClaimKind.REQUESTED).get(0).id();
		enrichment.applyResults(List.of(applied(served, null, List.of())), 100);
		UUID waiting = jdbc.queryForObject("select id from articles where status = 'EXCERPT_ONLY' limit 1", UUID.class);
		assertThat(requests.requestEnrichment(waiting)).isTrue();
	}

	@Test
	void anAttemptIsRefundedWhenTheArticleWasNotAtFaultButNotWhenItWas() {
		UUID id = article("PENDING", 0.5, 1);
		enrichment.claimBatch(1, ClaimKind.BACKGROUND);
		assertThat(attempts(id)).isEqualTo(1);

		enrichment.releaseWithoutAttempt(List.of(id));
		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(attempts(id)).isZero();

		enrichment.claimBatch(1, ClaimKind.BACKGROUND);
		enrichment.release(List.of(id));
		assertThat(attempts(id)).isEqualTo(1);

		enrichment.releaseWithoutAttempt(List.of(id)); // not ENRICHING any more: nothing changes
		assertThat(attempts(id)).isEqualTo(1);
		UUID fresh = article("PENDING", 0.4, 2);
		enrichment.releaseWithoutAttempt(List.of(fresh));
		assertThat(attempts(fresh)).isZero(); // never goes below zero
		enrichment.releaseWithoutAttempt(List.of());
		enrichment.releaseWithoutAttempt(null);
	}

	@Test
	void freeTagsLeftBehindByDeletedArticlesDoNotUseUpTheCap() {
		UUID gone = article("PENDING", 0.5, 1);
		enrichment.claimBatch(1, ClaimKind.BACKGROUND);
		enrichment.applyResults(List.of(applied(gone, null, List.of("old-one", "old-two"))), 2);
		jdbc.update("delete from articles where id = ?", gone);
		assertThat(jdbc.queryForObject("select count(*) from tags where kind = 'FREE'", Integer.class)).isEqualTo(2);

		UUID fresh = article("PENDING", 0.5, 2);
		enrichment.claimBatch(1, ClaimKind.BACKGROUND);
		enrichment.applyResults(List.of(applied(fresh, null, List.of("new-one", "new-two"))), 2);

		assertThat(tagsOf(fresh)).containsExactly("new-one", "new-two");
	}

	// ---- tags and integrity ----

	@Test
	void taxonomyTagsAreCreatedOncePromoteFreeTagsAndNeedAValidName() {
		enrichment.upsertTaxonomyTags(List.of("llm", "java"));
		enrichment.upsertTaxonomyTags(List.of("llm"));
		jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), 'rust', 'FREE', now())");
		enrichment.upsertTaxonomyTags(List.of("rust"));

		assertThat(jdbc.queryForObject("select count(*) from tags", Integer.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("select kind from tags where name = 'rust'", String.class)).isEqualTo("TAXONOMY");
		assertThatThrownBy(() -> enrichment.upsertTaxonomyTags(List.of("Not Valid"))).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void tagFormatKindAndOriginAreEnforcedByTheDatabase() {
		UUID id = article("PENDING", 0.5, 1);
		for (String bad : List.of("UPPER", "has space", "-leading", "", "a".repeat(31), "semi;colon", "quote'")) {
			assertThatThrownBy(() -> jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), ?, 'FREE', now())", bad))
					.as(bad).isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThatThrownBy(() -> jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), 'fine', 'OTHER', now())"))
				.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), 'c++', 'FREE', now())");
		jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), 'c#', 'FREE', now())");
		jdbc.update("insert into tags (id, name, kind, created_at) values (gen_random_uuid(), 'node.js', 'FREE', now())");
		assertThatThrownBy(() -> jdbc.update("insert into article_tags (article_id, tag_id, origin) select ?, id, 'HUMAN' from tags limit 1", id))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingAnArticleOrATagRemovesTheLinks() {
		UUID id = article("PENDING", 0.5, 1);
		enrichment.claimBatch(1, ClaimKind.BACKGROUND);
		enrichment.applyResults(List.of(applied(id, null, List.of("alpha", "beta"))), 100);

		jdbc.update("delete from tags where name = 'alpha'");
		assertThat(tagsOf(id)).containsExactly("beta");
		jdbc.update("delete from articles where id = ?", id);

		assertThat(jdbc.queryForObject("select count(*) from article_tags", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from tags where name = 'beta'", Integer.class)).isEqualTo(1);
	}

}
