package com.intellhub.articles;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Counts about the stored articles, for the admin status page. Nothing here carries article text. */
public interface ArticleStatistics {

	/** How many articles are in each status (EXCERPT_ONLY, PENDING, ENRICHING, ENRICHED, FAILED); absent statuses are 0. */
	Map<String, Long> countsByStatus();

	long total();

	/** The publish time of the oldest stored article, or empty if there are none. */
	Optional<Instant> oldestPublishedAt();

}
