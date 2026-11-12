package com.intellhub.feed.internal;

import com.intellhub.articles.ArticleView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The JSON shapes of the feed API.
 *
 * <p>Rendering contract: {@code contentSanitized} is the only field that may be shown as HTML. Titles,
 * authors, excerpts, summaries and source names are plain text and must be shown as text.
 */
final class FeedResponses {

	private FeedResponses() {
	}

	record FeedItem(UUID id, UUID sourceId, String sourceName, String url, String title, String author,
			Instant publishedAt, String excerpt, String summary, String category, String status, boolean saved) {

		static FeedItem of(ArticleView a, String sourceName, boolean saved) {
			return new FeedItem(a.id(), a.sourceId(), sourceName, a.canonicalUrl(), a.title(), a.author(),
					a.publishedAt(), a.excerpt(), a.summary(), a.category(), a.status(), saved);
		}

	}

	record ArticleDetail(UUID id, UUID sourceId, String sourceName, String url, String title, String author,
			Instant publishedAt, String excerpt, String summary, String category, String status, boolean saved,
			String contentSanitized) {

		static ArticleDetail of(ArticleView a, String sourceName, boolean saved) {
			return new ArticleDetail(a.id(), a.sourceId(), sourceName, a.canonicalUrl(), a.title(), a.author(),
					a.publishedAt(), a.excerpt(), a.summary(), a.category(), a.status(), saved, a.contentSanitized());
		}

	}

	record FeedPage(List<FeedItem> items, int page, int size, long totalElements, int totalPages) {
	}

}
