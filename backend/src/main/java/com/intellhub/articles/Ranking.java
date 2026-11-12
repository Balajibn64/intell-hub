package com.intellhub.articles;

import java.util.Map;

/**
 * How to order a search for one reader instead of newest first: each article gets a score from its own
 * relevance, how well its tags match what the reader cares about, and how recent it is (see
 * {@code docs/DATABASE-API.md} for the formula).
 *
 * @param tagWeights how much the reader cares about each tag (tag name to weight, 0 to {@value #MAX_WEIGHT}); at most
 * {@value #MAX_TAGS} tags, which the caller keeps to the strongest ones
 */
public record Ranking(Map<String, Double> tagWeights) {

	public static final int MAX_TAGS = 100;

	public static final double MAX_WEIGHT = 10.0;

	public Ranking {
		tagWeights = tagWeights == null ? Map.of() : Map.copyOf(tagWeights);
	}

	/** Ranking without a particular reader's interests: article relevance and recency only. */
	public static Ranking neutral() {
		return new Ranking(Map.of());
	}

}
