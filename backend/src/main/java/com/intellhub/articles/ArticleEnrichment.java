package com.intellhub.articles;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The articles module's published API for the enrichment pipeline. The articles module owns the rows, so
 * enrichment changes article state only through here. Every transition names the state it expects, so a
 * stale, repeated or concurrent call changes nothing.
 *
 * <p>States: PENDING, then ENRICHING while claimed, then ENRICHED, or back to PENDING, or FAILED. Articles
 * the cheap pre-filter skips are EXCERPT_ONLY until a reader asks for them.
 */
public interface ArticleEnrichment {

	/** Articles in PENDING that have not been through the pre-filter yet, newest first. */
	List<TriageCandidate> triageCandidates(int limit);

	/**
	 * Records the pre-filter score. If the article is not worth enriching it becomes EXCERPT_ONLY. Does nothing
	 * unless the article is PENDING and unscored.
	 */
	void recordTriage(UUID articleId, double heuristicScore, boolean worthEnriching);

	/**
	 * Claims up to {@code size} PENDING, scored articles for enrichment, requested ones first and then by score.
	 * One atomic statement: two workers always get different articles.
	 */
	List<EnrichmentInput> claimBatch(int size);

	/**
	 * Stores results for articles that are still ENRICHING and marks them ENRICHED. Results for articles in any
	 * other state are ignored. New FREE tags are created only while fewer than {@code maxFreeTags} exist.
	 */
	void applyResults(List<AppliedEnrichment> results, int maxFreeTags);

	/** Makes the given ENRICHING articles PENDING again. */
	void release(Collection<UUID> articleIds);

	/** Marks the given ENRICHING articles FAILED. */
	void fail(Collection<UUID> articleIds);

	/**
	 * Recovers work that was left behind: ENRICHING articles claimed longer ago than {@code stale} go back to
	 * PENDING (a crash or restart), and FAILED ones older than {@code failedRetryAfter} go back to PENDING with
	 * their attempts reset.
	 */
	Recovered recoverStale(Duration stale, Duration failedRetryAfter);

	/** A reader opened an article that was skipped: make it PENDING and first in line. False if it was in any other state. */
	boolean requestEnrichment(UUID articleId);

	/** Creates the curated tags (or promotes existing free tags of the same name). Names must match the tag format. */
	void upsertTaxonomyTags(Collection<String> names);

	record Recovered(int staleClaims, int retriedFailures) {
	}

}
