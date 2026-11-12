package com.intellhub.personalization;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The personalization module's published API: what a user has saved and clicked.
 *
 * <p>Every method takes the user id explicitly, and callers must pass the id of the authenticated user,
 * never one supplied by a client. An unknown user or article id is rejected by the database
 * (DataIntegrityViolationException), so callers should check that the article exists first.
 */
public interface ReadingState {

	/** Most saved articles ever returned by {@link #savedArticleIds}. */
	int MAX_SAVED_LISTED = 2000;

	/** Clicks on one article by one user closer together than this are recorded once. */
	int CLICK_DEDUPE_SECONDS = 10;

	/** Saves an article. Returns true if it was newly saved, false if it already was. */
	boolean save(UUID userId, UUID articleId);

	/** Removes a saved article. Returns true if it had been saved, false if it had not. */
	boolean unsave(UUID userId, UUID articleId);

	/**
	 * Records that the user opened the article. A repeat click on the same article within
	 * {@link #CLICK_DEDUPE_SECONDS} counts once, so a double-click or a UI that fires twice does not inflate
	 * the signal.
	 */
	void recordClick(UUID userId, UUID articleId);

	/** The user's saved article ids, most recently saved first, at most {@link #MAX_SAVED_LISTED}. */
	List<UUID> savedArticleIds(UUID userId);

	/** Which of the given articles the user has saved. */
	Set<UUID> savedAmong(UUID userId, Collection<UUID> articleIds);

}
