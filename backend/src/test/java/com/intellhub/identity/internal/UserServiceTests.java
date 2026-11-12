package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intellhub.identity.Role;
import com.intellhub.shared.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class UserServiceTests {

	private final UserRepository users = mock(UserRepository.class);

	private final UserService service = new UserService(users, new PasswordService(TestProperties.defaults()),
			Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

	private DataIntegrityViolationException violation(String constraint) {
		return new DataIntegrityViolationException("could not execute statement",
				new RuntimeException("ERROR: violates constraint \"" + constraint + "\""));
	}

	@Test
	void aRaceOnTheEmailUniqueConstraintIsReportedAsEmailTaken() {
		when(users.existsByEmail("a@example.com")).thenReturn(false);
		when(users.saveAndFlush(any(User.class))).thenThrow(violation("users_email_unique"));

		assertThatThrownBy(() -> service.create("a@example.com", "a-long-enough-password", Role.USER))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(e.getCode()).isEqualTo("EMAIL_TAKEN");
				});
	}

	@Test
	void otherConstraintFailuresAreNotDisguisedAsADuplicateEmail() {
		when(users.existsByEmail("a@example.com")).thenReturn(false);
		when(users.saveAndFlush(any(User.class))).thenThrow(violation("users_email_lowercase"));

		assertThatThrownBy(() -> service.create("a@example.com", "a-long-enough-password", Role.USER))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
