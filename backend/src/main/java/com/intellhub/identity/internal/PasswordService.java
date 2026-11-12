package com.intellhub.identity.internal;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** Hashes and checks passwords with BCrypt. Raw passwords never leave this class. */
@Service
class PasswordService {

	/** BCrypt only uses the first 72 bytes, so longer passwords would silently truncate. */
	static final int MAX_PASSWORD_BYTES = 72;

	private final BCryptPasswordEncoder encoder;

	private final String dummyHash;

	PasswordService(IdentityProperties properties) {
		this.encoder = new BCryptPasswordEncoder(properties.bcryptStrength());
		this.dummyHash = encoder.encode("not-a-real-password");
	}

	String hash(String rawPassword) {
		if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new IllegalArgumentException("Password is longer than " + MAX_PASSWORD_BYTES + " bytes");
		}
		return encoder.encode(rawPassword);
	}

	boolean matches(String rawPassword, String hash) {
		return encoder.matches(rawPassword, hash);
	}

	/**
	 * Spends the same time as a real check, for logins with an unknown email, so response time
	 * does not reveal whether an account exists.
	 */
	void burn(String rawPassword) {
		encoder.matches(rawPassword, dummyHash);
	}

}
