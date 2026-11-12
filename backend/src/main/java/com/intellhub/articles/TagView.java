package com.intellhub.articles;

/**
 * A tag and how many articles carry it.
 *
 * @param kind TAXONOMY (curated) or FREE (chosen by the model)
 */
public record TagView(String name, String kind, long articleCount) {
}
