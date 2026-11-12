package com.intellhub.articles.internal;

import java.util.Map;

/**
 * The ranking formula as SQL, in one place. Fixed text only: the reader's tag names and weights are always bound
 * parameters, and only their number (at most 100) shapes the text.
 *
 * <pre>
 * score = 0.4 * relevance + 0.4 * affinity + 0.2 * recency        (each part from 0 to 1)
 * relevance = relevance_score, else half the cheap heuristic score, else 0
 * affinity  = 1 - exp(-s), s = the reader's summed weights of the article's tags
 * recency   = 0.5 ^ (age / 72 hours)
 * </pre>
 */
final class RankingSql {

	static final double RELEVANCE_SHARE = 0.4;

	static final double AFFINITY_SHARE = 0.4;

	static final double RECENCY_SHARE = 0.2;

	static final double RECENCY_HALF_LIFE_HOURS = 72;

	/** The score, using the lateral {@code aff} (summed tag weight) and the bound {@code :now}. */
	static final String SCORE = "(" + RELEVANCE_SHARE + " * least(1, greatest(0, coalesce(relevance_score, 0.5 * heuristic_score, 0)))"
			+ " + " + AFFINITY_SHARE + " * (1 - exp(-coalesce(aff.s, 0)))"
			+ " + " + RECENCY_SHARE + " * power(0.5, greatest(extract(epoch from (:now - published_at)), 0) / ("
			+ RECENCY_HALF_LIFE_HOURS + " * 3600.0)))";

	private RankingSql() {
	}

	/**
	 * The join that sums the reader's weights over each article's tags. With no weights the sum is simply
	 * absent. The values list is numbered placeholders {@code :rn0, :rw0, :rn1, ...}.
	 */
	static String affinityJoin(int tagCount) {
		if (tagCount == 0) {
			return " left join lateral (select 0.0 as s) aff on true";
		}
		StringBuilder values = new StringBuilder();
		for (int i = 0; i < tagCount; i++) {
			values.append(i == 0 ? "" : ", ").append("(cast(:rn").append(i).append(" as text), cast(:rw").append(i)
					.append(" as double precision))");
		}
		return " left join lateral (select sum(w.weight) as s from article_tags rat"
				+ " join tags rt on rt.id = rat.tag_id"
				+ " join (values " + values + ") as w(name, weight) on w.name = rt.name"
				+ " where rat.article_id = articles.id) aff on true";
	}

	/** Binds the names and weights in the order {@link #affinityJoin} numbered them. */
	static org.springframework.jdbc.core.simple.JdbcClient.StatementSpec bindWeights(
			org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec, java.util.List<Map.Entry<String, Double>> weights) {
		for (int i = 0; i < weights.size(); i++) {
			spec = spec.param("rn" + i, weights.get(i).getKey()).param("rw" + i, weights.get(i).getValue());
		}
		return spec;
	}

}
