package com.intellhub.feed.internal;

import com.intellhub.articles.ArticleCatalog;
import com.intellhub.articles.EnrichmentRequests;
import com.intellhub.articles.ArticleFilter;
import com.intellhub.articles.ArticlePage;
import com.intellhub.articles.ArticleView;
import com.intellhub.feed.internal.FeedResponses.ArticleDetail;
import com.intellhub.feed.internal.FeedResponses.FeedItem;
import com.intellhub.feed.internal.FeedResponses.FeedPage;
import com.intellhub.feed.internal.FeedResponses.TagItem;
import com.intellhub.identity.CurrentUser;
import com.intellhub.personalization.ReadingState;
import com.intellhub.shared.ApiException;
import com.intellhub.sources.SourceCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reading side of the API. All articles are shared and visible to every signed-in user; what is
 * personal (the saved flag and the saved filter) is always looked up with the id from the verified token.
 */
@RestController
@RequestMapping("/api/v1")
class FeedController {

	private static final int DEFAULT_PAGE_SIZE = 20;

	private final ArticleCatalog articles;

	private final ReadingState readingState;

	private final SourceCatalog sources;

	private final EnrichmentRequests enrichment;

	FeedController(ArticleCatalog articles, ReadingState readingState, SourceCatalog sources, EnrichmentRequests enrichment) {
		this.articles = articles;
		this.readingState = readingState;
		this.sources = sources;
		this.enrichment = enrichment;
	}

	@GetMapping("/feed")
	FeedPage feed(CurrentUser user, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size, @RequestParam(required = false) String q,
			@RequestParam(required = false) UUID source, @RequestParam(required = false) String category,
			@RequestParam(required = false) String tag, @RequestParam(defaultValue = "false") boolean saved) {
		validate(page, size, q, category, tag);

		Set<UUID> onlyIds = saved ? new HashSet<>(readingState.savedArticleIds(user.id())) : null;
		ArticlePage result;
		try {
			result = articles.search(new ArticleFilter(q, source, category, tag, onlyIds), page, size);
		}
		catch (IllegalArgumentException e) {
			// The catalog is the authority on limits; if it is stricter than the checks above, still answer 400.
			throw invalid("Invalid search parameters");
		}

		List<UUID> ids = result.items().stream().map(ArticleView::id).toList();
		Set<UUID> savedAmongPage = readingState.savedAmong(user.id(), ids);
		Map<UUID, String> names = sources.names(result.items().stream().map(ArticleView::sourceId).collect(Collectors.toSet()));
		List<FeedItem> items = result.items().stream()
				.map(a -> FeedItem.of(a, names.get(a.sourceId()), savedAmongPage.contains(a.id()))).toList();
		return new FeedPage(items, result.page(), result.size(), result.totalElements(), result.totalPages());
	}

	/** The curated tags first, then the most used free tags. */
	@GetMapping("/tags")
	List<TagItem> tags(CurrentUser user, @RequestParam(defaultValue = "100") int limit) {
		if (limit < 1 || limit > ArticleCatalog.MAX_TAG_LIST) {
			throw invalid("limit must be between 1 and " + ArticleCatalog.MAX_TAG_LIST);
		}
		return articles.tags(limit).stream().map(t -> new TagItem(t.name(), t.kind(), t.articleCount())).toList();
	}

	@GetMapping("/articles/{id}")
	ArticleDetail article(CurrentUser user, @PathVariable UUID id) {
		ArticleView found = articles.find(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "Article not found"));
		ArticleView article = found;
		if ("EXCERPT_ONLY".equals(found.status()) && enrichment.requestEnrichment(id)) {
			// A reader opened an article the cheap pre-filter skipped: queue it for enrichment, first in line. It does
			// not wait for the result (the worker fills it in later); the response shows the state as it is now.
			article = articles.find(id).orElse(found);
		}
		String sourceName = sources.names(Set.of(article.sourceId())).get(article.sourceId());
		boolean saved = readingState.savedAmong(user.id(), Set.of(id)).contains(id);
		return ArticleDetail.of(article, sourceName, saved);
	}

	private static void validate(int page, int size, String q, String category, String tag) {
		if (page < 0 || page > ArticleCatalog.MAX_PAGE) {
			throw invalid("page must be between 0 and " + ArticleCatalog.MAX_PAGE);
		}
		if (size < 1 || size > ArticleCatalog.MAX_PAGE_SIZE) {
			throw invalid("size must be between 1 and " + ArticleCatalog.MAX_PAGE_SIZE);
		}
		if (q != null && q.length() > ArticleCatalog.MAX_QUERY_LENGTH) {
			throw invalid("q must be at most " + ArticleCatalog.MAX_QUERY_LENGTH + " characters");
		}
		if (tag != null && !ArticleCatalog.TAG_NAME.matcher(tag).matches()) {
			throw invalid("tag is not a valid tag name");
		}
		if (category != null && !ArticleCatalog.CATEGORIES.contains(category)) {
			throw invalid("category must be one of " + new java.util.TreeSet<>(ArticleCatalog.CATEGORIES));
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
	}

}
