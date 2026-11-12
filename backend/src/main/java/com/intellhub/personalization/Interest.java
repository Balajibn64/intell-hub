package com.intellhub.personalization;

/**
 * A tag the user chose to follow.
 *
 * @param tag the tag name
 * @param weight how much they care, from {@link UserInterests#MIN_WEIGHT} to {@link UserInterests#MAX_WEIGHT}
 */
public record Interest(String tag, double weight) {
}
