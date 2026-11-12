package com.intellhub.ingestion.internal;

import com.intellhub.shared.ApiError;
import com.intellhub.shared.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * A separate security chain for /internal/**, which runs before the user-facing one. User tokens are not
 * accepted here and the shared secret is not accepted anywhere else. Every refusal is the same 403, so a
 * caller cannot tell a wrong secret from a missing one from a disabled endpoint.
 */
@Configuration
class InternalSecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain internalSecurityFilterChain(HttpSecurity http, IngestionProperties properties,
			ApiErrorWriter errors) throws Exception {
		return http
				.securityMatcher("/internal/**")
				.csrf(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(new IngestTokenFilter(properties.ingestSecret()), AuthorizationFilter.class)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/internal/ingest").hasRole("INGEST")
						.anyRequest().denyAll())
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint((request, response, ex) -> errors.write(response, HttpStatus.FORBIDDEN,
								ApiError.of("FORBIDDEN", "Access denied")))
						.accessDeniedHandler((request, response, ex) -> errors.write(response, HttpStatus.FORBIDDEN,
								ApiError.of("FORBIDDEN", "Access denied"))))
				.build();
	}

}
