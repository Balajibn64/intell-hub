package com.intellhub.articles;

import java.util.Set;
import java.util.UUID;

/**
 * Optional restrictions for an article search. A null field means "no restriction".
 *
 * @param query words to search for in title, excerpt and summary (web-search syntax, at most 200 characters)
 * @param sourceId only articles from this source
 * @param category only articles in this category (AI_ML, SOFTWARE_ENGINEERING or INDUSTRY_STARTUPS)
 * @param tag only articles with this tag (lowercase, the usual tag format)
 * @param onlyIds only these articles; an empty set matches nothing
 * @param ranking if present, order by score for this reader instead of newest first
 */
public record ArticleFilter(String query, UUID sourceId, String category, String tag, Set<UUID> onlyIds, Ranking ranking) {

	public ArticleFilter(String query, UUID sourceId, String category, String tag, Set<UUID> onlyIds) {
		this(query, sourceId, category, tag, onlyIds, null);
	}

	public ArticleFilter(String query, UUID sourceId, String category, Set<UUID> onlyIds) {
		this(query, sourceId, category, null, onlyIds, null);
	}

	public static ArticleFilter none() {
		return new ArticleFilter(null, null, null, null, null, null);
	}

}
