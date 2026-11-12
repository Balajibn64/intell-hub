package com.intellhub.articles;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The articles module's published API for reading. Results are always newest first, with the id as a tie
 * breaker so equal timestamps never reorder between requests.
 */
public interface ArticleCatalog {

	int MAX_PAGE_SIZE = 50;

	int MAX_PAGE = 100_000;

	int MAX_QUERY_LENGTH = 200;

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

}
