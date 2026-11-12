package com.intellhub.articles.internal;

/** SQL fragments shared by the search code and the test that checks the index is usable. */
final class ArticleSql {

	/**
	 * The search document. It must be exactly the expression of the {@code articles_search_idx} index, or
	 * PostgreSQL cannot use the index.
	 */
	static final String SEARCH_VECTOR = "to_tsvector('english', title || ' ' || excerpt || ' ' || coalesce(summary, ''))";

	static final String COLUMNS = """
			id, source_id, canonical_url, title, author, published_at, ingested_at, excerpt, summary, category, status""";

	private ArticleSql() {
	}

}
