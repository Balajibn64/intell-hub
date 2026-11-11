package com.intellhub.identity.internal;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** HS256 access tokens signed with a shared secret. No OAuth flows are involved. */
@Configuration
class JwtConfig {

	@Bean
	JwtEncoder jwtEncoder(IdentityProperties properties) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
	}

	@Bean
	JwtDecoder jwtDecoder(IdentityProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
		timestamps.setClock(clock);
		OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(timestamps,
				new JwtIssuerValidator(properties.jwt().issuer()));
		decoder.setJwtValidator(validator);
		return decoder;
	}

	private static SecretKey secretKey(IdentityProperties properties) {
		return new SecretKeySpec(properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

}
