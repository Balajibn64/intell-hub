package com.intellhub.articles;

import java.util.List;
import java.util.UUID;

/**
 * Validated enrichment results for one article. The caller is responsible for validation; the store
 * re-checks the database rules (category and tag format) as a backstop.
 *
 * @param category one of the known categories, or null
 * @param relevance 0 to 1
 * @param tags lowercase tag names, already limited
 * @param tagOrigin HEURISTIC or LLM
 * @param provider who produced it, for example "fake"
 */
public record AppliedEnrichment(UUID id, String summary, String category, double relevance, List<String> tags,
		String tagOrigin, String provider) {
}
