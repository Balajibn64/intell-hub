package com.intellhub.personalization;

/**
 * What the user has for one tag.
 *
 * @param tag the tag name
 * @param explicit the weight they chose (0 if they did not choose this tag)
 * @param learned what their clicks and saves added, already faded for the time since
 */
public record InterestView(String tag, double explicit, double learned) {
}
