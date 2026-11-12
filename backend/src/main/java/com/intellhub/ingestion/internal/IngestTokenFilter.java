package com.intellhub.ingestion.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates calls to /internal/** with a shared secret in the X-Ingest-Token header.
 *
 * <p>Both values are hashed first and the hashes compared with {@link MessageDigest#isEqual}, which takes
 * the same time whatever matches or how long the guess is. If no secret is configured, nothing ever
 * authenticates. This filter only authenticates; the rules that allow or refuse live in the security chain.
 */
final class IngestTokenFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Ingest-Token";

	private static final int MAX_TOKEN_LENGTH = 256;

	private final byte[] secretDigest;

	IngestTokenFilter(String secret) {
		this.secretDigest = secret == null || secret.isBlank() ? null : sha256(secret);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (secretDigest != null) {
			String token = request.getHeader(HEADER);
			if (token != null && token.length() <= MAX_TOKEN_LENGTH && MessageDigest.isEqual(sha256(token), secretDigest)) {
				SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken
						.authenticated("ingest", null, List.of(new SimpleGrantedAuthority("ROLE_INGEST"))));
			}
		}
		chain.doFilter(request, response);
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

}
