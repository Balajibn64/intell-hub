package com.intellhub.articles;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The articles module's published API for the retention purge. Both methods must be called inside a transaction
 * (they refuse to run without one), because the row locks taken by {@link #lockExpired} only mean something until
 * that transaction ends: the caller locks, decides what to keep, and deletes, all in one transaction.
 */
public interface ArticleRetention {

	/**
	 * Selects up to {@code limit} articles published before {@code cutoff}, in the database's id order starting after
	 * {@code after} (null for the start), and locks them. A reader saving one of them at the same moment waits for the
	 * caller's transaction, so the decision to keep or delete cannot be overtaken by a save.
	 *
	 * @throws IllegalStateException if there is no transaction
	 * @throws IllegalArgumentException if the limit is not between 1 and 5000
	 */
	List<UUID> lockExpired(Instant cutoff, UUID after, int limit);

	/**
	 * The same selection as {@link #lockExpired} but without locking and without needing a transaction, for a dry
	 * run that deletes nothing and so must not make a reader's save wait.
	 *
	 * @throws IllegalArgumentException if the limit is not between 1 and 5000
	 */
	List<UUID> listExpired(Instant cutoff, UUID after, int limit);

	/**
	 * Deletes the given articles; their tag links and reading events go with them (the database cascades).
	 * Returns how many were deleted.
	 *
	 * @throws IllegalStateException if there is no transaction
	 */
	int delete(Collection<UUID> ids);

}
