package com.intellhub.articles.internal;

import com.intellhub.articles.AppliedEnrichment;
import com.intellhub.articles.ArticleEnrichment;
import com.intellhub.articles.ClaimKind;
import com.intellhub.articles.EnrichmentRequests;
import com.intellhub.articles.EnrichmentInput;
import com.intellhub.articles.TriageCandidate;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plain SQL, so each transition is one statement that names the state it expects. */
@Service
class ArticleEnrichmentService implements ArticleEnrichment, EnrichmentRequests {

	private final JdbcClient jdbc;

	private final Clock clock;

	ArticleEnrichmentService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<TriageCandidate> triageCandidates(int limit) {
		return jdbc.sql("""
				select id, title, excerpt from articles
				where status = 'PENDING' and heuristic_score is null
				order by published_at desc, id limit :limit""")
				.param("limit", limit)
				.query((rs, n) -> new TriageCandidate(rs.getObject("id", UUID.class), rs.getString("title"),
						rs.getString("excerpt")))
				.list();
	}

	@Override
	@Transactional
	public void recordTriage(UUID articleId, double heuristicScore, boolean worthEnriching) {
		jdbc.sql("""
				update articles set heuristic_score = :score,
				status = case when :worth then status else 'EXCERPT_ONLY' end
				where id = :id and status = 'PENDING' and heuristic_score is null""")
				.param("score", heuristicScore).param("worth", worthEnriching).param("id", articleId).update();
	}

	@Override
	@Transactional
	public List<EnrichmentInput> claimBatch(int size, ClaimKind kind) {
		if (size < 1) {
			return List.of();
		}
		// Both variants are fixed text; only the kind (an enum) chooses between them.
		String filter = kind == ClaimKind.REQUESTED ? "and enrichment_requested_at is not null"
				: "and enrichment_requested_at is null";
		String order = kind == ClaimKind.REQUESTED ? "enrichment_requested_at, id"
				: "heuristic_score desc, published_at desc, id";
		String sql = """
				with picked as (
				    select id from articles
				    where status = 'PENDING' and heuristic_score is not null %s
				    order by %s
				    limit :size
				    for update skip locked)
				update articles a
				set status = 'ENRICHING', claimed_at = :now, enrichment_attempts = a.enrichment_attempts + 1
				from picked
				where a.id = picked.id
				returning a.id, a.title, a.excerpt, (a.enrichment_requested_at is not null) as requested,
				          a.enrichment_attempts""".formatted(filter, order);
		return jdbc.sql(sql)
				.param("size", size).param("now", now())
				.query((rs, n) -> new EnrichmentInput(rs.getObject("id", UUID.class), rs.getString("title"),
						rs.getString("excerpt"), rs.getBoolean("requested"), rs.getInt("enrichment_attempts")))
				.list();
	}

	@Override
	@Transactional
	public void applyResults(List<AppliedEnrichment> results, int maxFreeTags) {
		for (AppliedEnrichment result : results) {
			int updated = jdbc.sql("""
					update articles set summary = :summary, category = :category, relevance_score = :relevance,
					status = 'ENRICHED', enriched_at = :now, enriched_by = :provider
					where id = :id and status = 'ENRICHING'""")
					.param("summary", result.summary()).param("category", result.category())
					.param("relevance", result.relevance()).param("now", now()).param("provider", result.provider())
					.param("id", result.id()).update();
			if (updated == 0) {
				continue; // no longer ENRICHING: a stale or repeated result
			}
			jdbc.sql("delete from article_tags where article_id = :id and origin = :origin")
					.param("id", result.id()).param("origin", result.tagOrigin()).update();
			for (String name : result.tags()) {
				linkTag(result.id(), name, result.tagOrigin(), maxFreeTags);
			}
		}
	}

	private void linkTag(UUID articleId, String name, String origin, int maxFreeTags) {
		UUID tagId = jdbc.sql("select id from tags where name = :name").param("name", name).query(UUID.class)
				.optional().orElse(null);
		if (tagId == null) {
			// Only free tags that some article still uses count, so tags left behind by purged articles cannot
			// use up the cap.
			long freeTags = jdbc.sql("""
					select count(*) from tags t where t.kind = 'FREE'
					and exists (select 1 from article_tags at where at.tag_id = t.id)""").query(Long.class).single();
			if (freeTags >= maxFreeTags) {
				return; // a model cannot flood the tag table
			}
			jdbc.sql("insert into tags (id, name, kind, created_at) values (:id, :name, 'FREE', :now) on conflict (name) do nothing")
					.param("id", UUID.randomUUID()).param("name", name).param("now", now()).update();
			tagId = jdbc.sql("select id from tags where name = :name").param("name", name).query(UUID.class).single();
		}
		jdbc.sql("""
				insert into article_tags (article_id, tag_id, origin) values (:article, :tag, :origin)
				on conflict (article_id, tag_id) do nothing""")
				.param("article", articleId).param("tag", tagId).param("origin", origin).update();
	}

	@Override
	@Transactional
	public void release(Collection<UUID> articleIds) {
		transition(articleIds, "PENDING");
	}

	@Override
	@Transactional
	public void releaseWithoutAttempt(Collection<UUID> articleIds) {
		if (articleIds == null || articleIds.isEmpty()) {
			return;
		}
		jdbc.sql("""
				update articles set status = 'PENDING', enrichment_attempts = greatest(enrichment_attempts - 1, 0)
				where id in (:ids) and status = 'ENRICHING'""").param("ids", articleIds).update();
	}

	@Override
	@Transactional
	public void fail(Collection<UUID> articleIds) {
		transition(articleIds, "FAILED");
	}

	private void transition(Collection<UUID> articleIds, String to) {
		if (articleIds == null || articleIds.isEmpty()) {
			return;
		}
		jdbc.sql("update articles set status = '" + to + "' where id in (:ids) and status = 'ENRICHING'")
				.param("ids", articleIds).update();
	}

	@Override
	@Transactional
	public Recovered recoverStale(Duration stale, Duration failedRetryAfter) {
		OffsetDateTime now = now();
		int claims = jdbc.sql("""
				update articles set status = 'PENDING'
				where status = 'ENRICHING' and claimed_at < :cutoff""")
				.param("cutoff", now.minus(stale)).update();
		int retried = jdbc.sql("""
				update articles set status = 'PENDING', enrichment_attempts = 0
				where status = 'FAILED' and claimed_at < :cutoff""")
				.param("cutoff", now.minus(failedRetryAfter)).update();
		return new Recovered(claims, retried);
	}

	@Override
	@Transactional
	public boolean requestEnrichment(UUID articleId) {
		return jdbc.sql("""
				update articles set status = 'PENDING', enrichment_requested_at = :now
				where id = :id and status = 'EXCERPT_ONLY'
				and (select count(*) from articles where enrichment_requested_at is not null
				     and status in ('PENDING', 'ENRICHING')) < :max""")
				.param("now", now()).param("id", articleId).param("max", EnrichmentRequests.MAX_QUEUED_REQUESTS).update() > 0;
	}

	@Override
	@Transactional
	public void upsertTaxonomyTags(Collection<String> names) {
		for (String name : names) {
			jdbc.sql("""
					insert into tags (id, name, kind, created_at) values (:id, :name, 'TAXONOMY', :now)
					on conflict (name) do update set kind = 'TAXONOMY'""")
					.param("id", UUID.randomUUID()).param("name", name).param("now", now()).update();
		}
	}

	private OffsetDateTime now() {
		return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

}
