package com.intellhub.articles;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The articles module's published API for reading. Results are newest first unless the filter carries a
 * {@link Ranking}; either way the id is a tie breaker so equal values never reorder between requests.
 */
public interface ArticleCatalog {

	int MAX_PAGE_SIZE = 50;

	int MAX_PAGE = 100_000;

	int MAX_QUERY_LENGTH = 200;

	int MAX_TAG_LIST = 200;

	/** The one definition of a valid tag name (the database repeats it as a constraint). */
	java.util.regex.Pattern TAG_NAME = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9+#.-]{0,29}$");

	Set<String> CATEGORIES = Set.of("AI_ML", "SOFTWARE_ENGINEERING", "INDUSTRY_STARTUPS");

	/**
	 * Searches articles. List items carry no content.
	 *
	 * @throws IllegalArgumentException if the page, size, query or category is out of range
	 */
	ArticlePage search(ArticleFilter filter, int page, int size);

	/** One article including its sanitized content. */
	Optional<ArticleView> find(UUID id);

	boolean existsById(UUID id);

	/**
	 * Tags with the number of articles that carry them: the curated tags first (all of them, even with no
	 * articles), then the most used free tags that at least one article carries, at most {@code limit} in total.
	 *
	 * @throws IllegalArgumentException if the limit is out of range
	 */
	java.util.List<TagView> tags(int limit);

	/** The ids of the tags that exist among the given names (unknown names are simply absent from the result). */
	java.util.Map<String, UUID> tagIds(java.util.Collection<String> names);

	/** The names of the given tags (unknown ids are absent from the result). */
	java.util.Map<UUID, String> tagNames(java.util.Collection<UUID> ids);

	/** The ids of the tags an article carries; empty if it has none or does not exist. */
	java.util.List<UUID> tagIdsOf(UUID articleId);

}
