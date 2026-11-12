package com.intellhub.articles;

/** The articles module's published API for other modules, mainly ingestion. */
public interface ArticleStore {

	/**
	 * Stores the article unless it is a duplicate. Safe to call again for the same article, which makes
	 * ingestion idempotent.
	 *
	 * @throws IllegalArgumentException if a field is missing or over its size limit
	 */
	StoreOutcome store(NewArticle article);

}
