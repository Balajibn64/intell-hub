package com.intellhub.articles;

import java.util.UUID;

/**
 * The narrow port the reading side uses to ask for an article to be enriched. It is separate from the worker's
 * {@link ArticleEnrichment} so that code which only serves readers cannot claim, fail or rewrite articles.
 */
public interface EnrichmentRequests {

	/**
	 * Most readers' requests that may be waiting at once. A reader can ask for any article the cheap pre-filter
	 * skipped, so the queue is bounded; otherwise one script walking article ids could fill it.
	 */
	int MAX_QUEUED_REQUESTS = 50;

	/**
	 * Queues an article the pre-filter skipped, first in line. Returns false, changing nothing, if the article is
	 * in any other state, was already queued, or too many requests are already waiting.
	 */
	boolean requestEnrichment(UUID articleId);

}
