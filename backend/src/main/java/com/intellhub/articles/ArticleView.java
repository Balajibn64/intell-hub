package com.intellhub.articles;

import java.time.Instant;
import java.util.UUID;

/**
 * An article as shown to users. Only {@code contentSanitized} may ever be rendered as HTML; every other
 * text field (title, author, excerpt, summary) is plain text and must be shown as text.
 *
 * <p>Internal fields (content hash, enrichment attempts and scores) are deliberately not here.
 *
 * @param contentSanitized allowlisted HTML, or null in list views
 */
public record ArticleView(UUID id, UUID sourceId, String canonicalUrl, String title, String author, Instant publishedAt,
		Instant ingestedAt, String excerpt, String summary, String category, String status, String contentSanitized) {

	/** The same article without its content, for list views. */
	public ArticleView withoutContent() {
		return new ArticleView(id, sourceId, canonicalUrl, title, author, publishedAt, ingestedAt, excerpt, summary, category,
				status, null);
	}

}
