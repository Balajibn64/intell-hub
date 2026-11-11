package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.identity.Role;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtServiceTests {

	private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

	private final MutableClock clock = new MutableClock(START);

	private final JwtConfig config = new JwtConfig();

	private final IdentityProperties props = TestProperties.defaults();

	private final JwtService service = new JwtService(config.jwtEncoder(props), props, clock);

	private final JwtDecoder decoder = config.jwtDecoder(props, clock);

	@Test
	void issuedTokenCarriesSubjectRoleIssuerAndExpiry() {
		UUID userId = UUID.randomUUID();

		JwtService.AccessToken token = service.issue(userId, Role.ADMIN);
		Jwt jwt = decoder.decode(token.value());

		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getClaimAsString(JwtService.ROLE_CLAIM)).isEqualTo("ADMIN");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("intellhub");
		assertThat(jwt.getExpiresAt()).isEqualTo(START.plus(Duration.ofMinutes(15)));
		assertThat(token.ttl()).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void tokenIsAcceptedUntilItExpiresAndRejectedAfter() {
		String token = service.issue(UUID.randomUUID(), Role.USER).value();

		clock.advance(Duration.ofMinutes(14));
		assertThat(decoder.decode(token)).isNotNull();

		clock.advance(Duration.ofMinutes(2));
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() {
		IdentityProperties other = TestProperties.with("a-completely-different-secret-0123456789", "intellhub");
		String forged = new JwtService(config.jwtEncoder(other), other, clock).issue(UUID.randomUUID(), Role.ADMIN)
				.value();

		assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
	}

	@Test
	void tokenFromAnotherIssuerIsRejected() {
		IdentityProperties other = TestProperties.with(TestProperties.SECRET, "someone-else");
		String token = new JwtService(config.jwtEncoder(other), other, clock).issue(UUID.randomUUID(), Role.USER)
				.value();

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void tokenUsingAnotherHmacAlgorithmIsRejected() {
		String longSecret = "s".repeat(80);
		IdentityProperties strong = TestProperties.with(longSecret, "intellhub");
		JwtEncoder encoder = config.jwtEncoder(strong);
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("intellhub").subject("x").issuedAt(START)
				.expiresAt(START.plusSeconds(600)).claim(JwtService.ROLE_CLAIM, "ADMIN").build();
		String hs512 = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS512).build(), claims))
				.getTokenValue();
		JwtDecoder strongDecoder = config.jwtDecoder(strong, clock);

		assertThatThrownBy(() -> strongDecoder.decode(hs512)).isInstanceOf(JwtException.class);
	}

	@Test
	void unsignedTokenIsRejected() {
		String unsigned = new PlainJWT(new JWTClaimsSet.Builder().issuer("intellhub").subject("x")
				.expirationTime(Date.from(START.plusSeconds(600))).claim(JwtService.ROLE_CLAIM, "ADMIN").build())
				.serialize();

		assertThatThrownBy(() -> decoder.decode(unsigned)).isInstanceOf(JwtException.class);
	}

	@Test
	void tamperedPayloadIsRejected() {
		String token = service.issue(UUID.randomUUID(), Role.USER).value();
		String[] parts = token.split("\\.");
		String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
				("{\"sub\":\"x\",\"role\":\"ADMIN\",\"iss\":\"intellhub\",\"exp\":" + START.plusSeconds(600).getEpochSecond() + "}")
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

		assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
	}

}
