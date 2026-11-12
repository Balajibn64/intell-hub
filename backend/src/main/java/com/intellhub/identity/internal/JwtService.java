package com.intellhub.identity.internal;

import com.intellhub.identity.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived access tokens. Verification is done by the resource-server decoder. */
@Service
class JwtService {

	static final String ROLE_CLAIM = "role";

	private final JwtEncoder encoder;

	private final IdentityProperties properties;

	private final Clock clock;

	JwtService(JwtEncoder encoder, IdentityProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	AccessToken issue(UUID userId, Role role) {
		Instant now = clock.instant();
		Duration ttl = properties.jwt().accessTtl();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.jwt().issuer())
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(now.plus(ttl))
				.claim(ROLE_CLAIM, role.name())
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(value, ttl);
	}

	record AccessToken(String value, Duration ttl) {
	}

}
