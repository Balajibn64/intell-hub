package com.intellhub.identity;

import java.time.Instant;

/** The identity module's published API for housekeeping of login tokens. */
public interface TokenMaintenance {

	/**
	 * Deletes refresh tokens that expired before the given instant. Tokens that were revoked or replaced but have
	 * not expired are kept on purpose: presenting one again is how reuse of a stolen token is noticed, and an
	 * expired token cannot be used for anything. Returns how many were deleted.
	 */
	int purgeExpiredRefreshTokens(Instant expiredBefore);

}
