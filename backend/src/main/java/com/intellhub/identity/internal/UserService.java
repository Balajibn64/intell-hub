package com.intellhub.identity.internal;

import com.intellhub.identity.Role;
import com.intellhub.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class UserService {

	static final int MIN_PASSWORD_LENGTH = 12;

	private final UserRepository users;

	private final PasswordService passwords;

	private final Clock clock;

	UserService(UserRepository users, PasswordService passwords, Clock clock) {
		this.users = users;
		this.passwords = passwords;
		this.clock = clock;
	}

	/** Creates an account. Emails are stored lowercase and must be unique. */
	User create(String email, String password, Role role) {
		String normalized = AuthService.normalizeEmail(email);
		if (password.length() < MIN_PASSWORD_LENGTH) {
			throw badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > PasswordService.MAX_PASSWORD_BYTES) {
			throw badRequest("Password must be at most " + PasswordService.MAX_PASSWORD_BYTES + " bytes");
		}
		if (users.existsByEmail(normalized)) {
			throw emailTaken();
		}
		try {
			return users.saveAndFlush(
					new User(UUID.randomUUID(), normalized, passwords.hash(password), role, clock.instant()));
		}
		catch (DataIntegrityViolationException e) {
			// Lost a race with a concurrent request for the same email. Any other constraint failure
			// is a real error and must not be disguised as a duplicate.
			if (isEmailUniqueViolation(e)) {
				throw emailTaken();
			}
			throw e;
		}
	}

	private static boolean isEmailUniqueViolation(DataIntegrityViolationException e) {
		String message = e.getMostSpecificCause().getMessage();
		return message != null && message.contains("users_email_unique");
	}

	List<User> listAll() {
		return users.findAllByOrderByCreatedAtAsc();
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email is already in use");
	}

	private static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
	}

}
