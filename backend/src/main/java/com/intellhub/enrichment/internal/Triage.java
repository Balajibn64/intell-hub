package com.intellhub.enrichment.internal;

import com.intellhub.articles.ArticleEnrichment;
import com.intellhub.articles.TriageCandidate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The cheap pre-filter. It scores new articles by taxonomy keyword hits (no provider call, so no budget) and
 * records the score, which also orders the queue. Articles that score below the threshold are only shown
 * with their excerpt until a reader asks for more.
 */
@Component
class Triage {

	private final ArticleEnrichment articles;

	private final Taxonomy taxonomy;

	private final EnrichmentProperties properties;

	Triage(ArticleEnrichment articles, Taxonomy taxonomy, EnrichmentProperties properties) {
		this.articles = articles;
		this.taxonomy = taxonomy;
		this.properties = properties;
	}

	/** Scores up to {@code limit} waiting articles and returns how many were handled. */
	int run(int limit) {
		List<TriageCandidate> candidates = articles.triageCandidates(limit);
		for (TriageCandidate candidate : candidates) {
			double score = taxonomy.score(candidate.title(), candidate.excerpt());
			articles.recordTriage(candidate.id(), score, score >= properties.triageThreshold());
		}
		return candidates.size();
	}

}
