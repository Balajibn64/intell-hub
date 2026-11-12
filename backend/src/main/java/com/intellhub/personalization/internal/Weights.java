package com.intellhub.personalization.internal;

/**
 * Every number that decides how clicks and saves change a user's weights, in one place so they can be tuned
 * (and tested) together. A learned weight is kept between 0 and 1 and fades by half every
 * {@value #HALF_LIFE_DAYS} days.
 */
final class Weights {

	static final double CLICK_STEP = 0.05;

	static final double SAVE_STEP = 0.15;

	static final double UNSAVE_STEP = -0.15;

	static final double HALF_LIFE_DAYS = 14;

	/** A learned weight below this is not worth listing. */
	static final double LISTING_THRESHOLD = 0.05;

	/**
	 * The faded learned weight as a SQL expression over {@code learned_weight} and {@code updated_at}, with the
	 * current time as the named parameter {@code now}. Fixed text: nothing from a caller is ever part of it.
	 */
	static final String FADED_LEARNED = fadedLearned("");

	/**
	 * The same expression with the columns qualified by a table name, for statements where an unqualified column
	 * would be ambiguous (the update part of an upsert). The qualifier is always a fixed constant.
	 */
	static String fadedLearned(String qualifier) {
		String prefix = qualifier.isEmpty() ? "" : qualifier + ".";
		return "(" + prefix + "learned_weight * power(0.5, greatest(extract(epoch from (:now - " + prefix
				+ "updated_at)), 0) / (" + HALF_LIFE_DAYS + " * 86400.0)))";
	}

	/** The per-user lock key shared by every writer of that user's weights. */
	static String lockKey(java.util.UUID userId) {
		return "user-tag-weights:" + userId;
	}

	private Weights() {
	}

}
