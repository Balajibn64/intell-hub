package com.intellhub.personalization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The personalization module's published API for what a user cares about: the tags they chose and the weights
 * their clicks and saves have added. Every method takes the user id explicitly; callers must pass the id of the
 * authenticated user, never one supplied by a client.
 */
public interface UserInterests {

	int MAX_INTERESTS = 50;

	double MIN_WEIGHT = 0.1;

	double MAX_WEIGHT = 1.0;

	/** Most tags returned by {@link #interests} and {@link #rankingWeights}. */
	int MAX_LISTED = 100;

	/**
	 * Makes the given tags the user's chosen interests, replacing the earlier choice. A tag that is no longer
	 * chosen loses its chosen weight but keeps what was learned from clicks and saves. Repeated tags keep the
	 * highest weight.
	 *
	 * @throws UnknownTagException if some tag does not exist (nothing is changed)
	 * @throws IllegalArgumentException if there are too many tags or a weight is out of range
	 */
	void replaceInterests(UUID userId, List<Interest> interests);

	/** The user's chosen tags and the tags they learned a noticeable weight for, strongest first, at most {@link #MAX_LISTED}. */
	List<InterestView> interests(UUID userId);

	/** The effective weight (chosen plus learned and faded) of each tag the user has, strongest first, at most {@link #MAX_LISTED}. */
	Map<String, Double> rankingWeights(UUID userId);

}
