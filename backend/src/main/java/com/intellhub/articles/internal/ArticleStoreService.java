package com.intellhub.articles.internal;

import com.intellhub.articles.ArticleStore;
import com.intellhub.articles.NewArticle;
import com.intellhub.articles.StoreOutcome;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Deliberately not transactional at this level: each repository call has its own short transaction.
 * A lost race on the unique URL then surfaces as an exception we can catch and report as a duplicate,
 * instead of poisoning an outer transaction.
 */
@Service
class ArticleStoreService implements ArticleStore {

	private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

	private final ArticleRepository articles;

	private final Clock clock;

	ArticleStoreService(ArticleRepository articles, Clock clock) {
		this.articles = articles;
		this.clock = clock;
	}

	@Override
	public StoreOutcome store(NewArticle a) {
		validate(a);
		if (articles.existsByCanonicalUrl(a.canonicalUrl())) {
			return StoreOutcome.DUPLICATE_URL;
		}
		if (articles.existsByContentHash(a.contentHash())) {
			return StoreOutcome.DUPLICATE_CONTENT;
		}
		try {
			articles.saveAndFlush(new Article(UUID.randomUUID(), a.sourceId(), a.canonicalUrl(), a.contentHash(),
					a.title(), a.author(), a.publishedAt(), clock.instant(), a.excerpt(), a.contentSanitized()));
			return StoreOutcome.CREATED;
		}
		catch (DataIntegrityViolationException e) {
			String message = e.getMostSpecificCause().getMessage();
			if (message != null && message.contains("articles_canonical_url_unique")) {
				return StoreOutcome.DUPLICATE_URL;
			}
			if (message != null && message.contains("articles_content_hash_unique")) {
				return StoreOutcome.DUPLICATE_CONTENT;
			}
			throw e;
		}
	}

	private static void validate(NewArticle a) {
		require(a != null, "article is required");
		require(a.sourceId() != null, "sourceId is required");
		require(a.publishedAt() != null, "publishedAt is required");
		require(a.canonicalUrl() != null && !a.canonicalUrl().isBlank() && a.canonicalUrl().length() <= NewArticle.MAX_URL,
				"canonicalUrl is missing or too long");
		require(a.contentHash() != null && SHA256_HEX.matcher(a.contentHash()).matches(),
				"contentHash must be 64 lowercase hex characters");
		require(a.title() != null && !a.title().isBlank() && a.title().length() <= NewArticle.MAX_TITLE,
				"title is missing or too long");
		require(a.author() == null || a.author().length() <= NewArticle.MAX_AUTHOR, "author is too long");
		require(a.excerpt() != null && a.excerpt().length() <= NewArticle.MAX_EXCERPT, "excerpt is missing or too long");
		require(a.contentSanitized() != null && a.contentSanitized().length() <= NewArticle.MAX_CONTENT,
				"content is missing or too long");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}

}
