package com.intellhub.articles;

import java.util.UUID;

/**
 * An article claimed for enrichment. Only plain text that is already bounded (title and excerpt) is
 * included, never the stored HTML.
 *
 * @param requested a reader asked for this one, so it may use the reserved part of the daily budget
 * @param attempts how many times it has now been claimed, including this time
 */
public record EnrichmentInput(UUID id, String title, String excerpt, boolean requested, int attempts) {
}
