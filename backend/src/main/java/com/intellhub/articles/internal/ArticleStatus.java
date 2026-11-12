package com.intellhub.articles.internal;

/** Where an article is in enrichment. New articles start as PENDING. */
enum ArticleStatus {
	EXCERPT_ONLY,
	PENDING,
	ENRICHING,
	ENRICHED,
	FAILED
}
