package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class IdentityPropertiesTests {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void validPropertiesPass() {
		assertThat(validator.validate(TestProperties.defaults())).isEmpty();
	}

	@Test
	void shortJwtSecretIsRejected() {
		assertThat(validator.validate(TestProperties.with("too-short", "intellhub"))).isNotEmpty();
	}

	@Test
	void blankJwtSecretIsRejected() {
		assertThat(validator.validate(TestProperties.with("", "intellhub"))).isNotEmpty();
	}

	@Test
	void theExamplePlaceholderSecretIsRejectedEvenThoughItIsLongEnough() {
		String placeholder = "change-me-to-a-long-random-value-of-32-chars-or-more";

		assertThat(placeholder.length()).isGreaterThan(32);
		assertThat(validator.validate(TestProperties.with(placeholder, "intellhub"))).isNotEmpty();
		assertThat(validator.validate(TestProperties.with("CHANGE-ME-" + "x".repeat(40), "intellhub"))).isNotEmpty();
		assertThat(validator.validate(TestProperties.with("a-real-random-secret-0123456789abcdef-xyz", "intellhub")))
				.isEmpty();
	}

	@Test
	void adminIsOnlyConfiguredWhenBothValuesArePresent() {
		assertThat(new IdentityProperties.Admin(null, null).configured()).isFalse();
		assertThat(new IdentityProperties.Admin("a@b.c", "").configured()).isFalse();
		assertThat(new IdentityProperties.Admin("a@b.c", "long-enough-password").configured()).isTrue();
	}

}
