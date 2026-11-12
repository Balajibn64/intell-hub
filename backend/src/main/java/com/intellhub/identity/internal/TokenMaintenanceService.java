package com.intellhub.identity.internal;

import com.intellhub.identity.TokenMaintenance;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TokenMaintenanceService implements TokenMaintenance {

	private final RefreshTokenRepository tokens;

	TokenMaintenanceService(RefreshTokenRepository tokens) {
		this.tokens = tokens;
	}

	@Override
	@Transactional
	public int purgeExpiredRefreshTokens(Instant expiredBefore) {
		if (expiredBefore == null) {
			throw new IllegalArgumentException("a cutoff is required");
		}
		return tokens.deleteExpiredBefore(expiredBefore);
	}

}
