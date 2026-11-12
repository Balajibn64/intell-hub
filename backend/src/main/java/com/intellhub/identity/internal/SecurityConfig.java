package com.intellhub.identity.internal;

import com.intellhub.shared.ApiError;
import com.intellhub.shared.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless JWT bearer security.
 *
 * <p>CSRF protection is off for the API because it authenticates with an Authorization header, not
 * an ambient cookie. The only cookie endpoints (refresh and logout) are guarded separately in
 * {@link CookieGuard}, so the exemption is as small as possible.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errors) throws Exception {
		AuthenticationEntryPoint unauthenticated = (request, response, ex) -> {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			errors.write(response, HttpStatus.UNAUTHORIZED,
					ApiError.of("UNAUTHENTICATED", "Authentication required"));
		};
		AccessDeniedHandler forbidden = (request, response, ex) -> errors.write(response, HttpStatus.FORBIDDEN,
				ApiError.of("FORBIDDEN", "Access denied"));

		return http
				.csrf(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
								"/api/v1/auth/logout").permitAll()
						// Reserved for the ingestion milestone, which adds its own shared-secret check.
						.requestMatchers("/internal/**").denyAll()
						// Every admin route needs ADMIN, so a new controller that forgets @PreAuthorize is still safe.
						.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth -> oauth
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
						.authenticationEntryPoint(unauthenticated)
						.accessDeniedHandler(forbidden))
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(unauthenticated)
						.accessDeniedHandler(forbidden))
				.build();
	}

	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(JwtService.ROLE_CLAIM);
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

}
