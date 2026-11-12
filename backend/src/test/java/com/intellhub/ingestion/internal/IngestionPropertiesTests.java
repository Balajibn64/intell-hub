package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IngestionPropertiesTests {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private static IngestionProperties with(String secret, int maxEntries, int concurrency) {
		return new IngestionProperties(secret, false, "0 0 */4 * * *", Duration.ofSeconds(5), Duration.ofSeconds(10),
				Duration.ofSeconds(20), 2_097_152, 3, maxEntries, Duration.ofDays(14), concurrency, Duration.ofMinutes(3));
	}

	@Test
	void validSettingsPass() {
		assertThat(validator.validate(with("s".repeat(32), 100, 4))).isEmpty();
		assertThat(validator.validate(with(null, 100, 4))).isEmpty();
		assertThat(validator.validate(with("", 100, 4))).isEmpty();
	}

	@Test
	void aSecretThatIsSetButShortIsRejected() {
		assertThat(validator.validate(with("short", 100, 4))).isNotEmpty();
		assertThat(validator.validate(with("s".repeat(31), 100, 4))).isNotEmpty();
		assertThat(validator.validate(with("s".repeat(32), 100, 4))).isEmpty();
	}

	@Test
	void thePlaceholderSecretFromTheExampleFileIsRejected() {
		assertThat(validator.validate(with("change-me-to-a-long-random-value-of-32-chars-or-more", 100, 4))).isNotEmpty();
		assertThat(validator.validate(with("Change-Me-" + "x".repeat(40), 100, 4))).isNotEmpty();
	}

	@Test
	void anUnsetOrBlankSecretMeansTheEndpointIsOff() {
		assertThat(with(null, 100, 4).secretConfigured()).isFalse();
		assertThat(with("   ", 100, 4).secretConfigured()).isFalse();
		assertThat(with("s".repeat(32), 100, 4).secretConfigured()).isTrue();
	}

	@Test
	void limitsAreBounded() {
		assertThat(validator.validate(with(null, 0, 4))).isNotEmpty();
		assertThat(validator.validate(with(null, 5000, 4))).isNotEmpty();
		assertThat(validator.validate(with(null, 100, 0))).isNotEmpty();
		assertThat(validator.validate(with(null, 100, 99))).isNotEmpty();
	}

}
