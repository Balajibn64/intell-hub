package com.intellhub.articles;

import java.time.Instant;
import java.util.UUID;

/**
 * An article ready to be stored. Everything here must already be cleaned: plain-text title, author and
 * excerpt, sanitized HTML for the content, and a canonical URL. The store enforces the size limits.
 *
 * @param sourceId the source it came from
 * @param canonicalUrl the normalised article URL, unique across all articles
 * @param contentHash SHA-256 hex of the normalised content, used to catch the same article under another URL
 * @param title plain text, at most 300 characters
 * @param author plain text, at most 200 characters, may be null
 * @param publishedAt when it was published (callers fall back to the fetch time)
 * @param excerpt plain text, at most 500 characters
 * @param contentSanitized allowlisted HTML, at most 50,000 characters
 */
public record NewArticle(UUID sourceId, String canonicalUrl, String contentHash, String title, String author,
		Instant publishedAt, String excerpt, String contentSanitized) {

	public static final int MAX_URL = 2048;

	public static final int MAX_TITLE = 300;

	public static final int MAX_AUTHOR = 200;

	public static final int MAX_EXCERPT = 500;

	public static final int MAX_CONTENT = 50_000;

}
