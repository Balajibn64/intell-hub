package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class EnrichmentPropertiesTests {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private static EnrichmentProperties with(String provider, int budget, int reserved, int batch, double threshold) {
		return new EnrichmentProperties(provider, budget, reserved, batch, 3, Duration.ofSeconds(30), Duration.ofMinutes(10), 20,
				ZoneId.of("UTC"), 5000, threshold);
	}

	@Test
	void theDefaultsAreValid() {
		assertThat(validator.validate(with(null, 250, 50, 5, 0.15))).isEmpty();
		assertThat(validator.validate(with("fake", 250, 50, 5, 0.15))).isEmpty();
	}

	@Test
	void unsetAndBlankProvidersMeanOff() {
		assertThat(with(null, 250, 50, 5, 0.15).enabled()).isFalse();
		assertThat(with("", 250, 50, 5, 0.15).enabled()).isFalse();
		assertThat(with("  ", 250, 50, 5, 0.15).enabled()).isFalse();
		assertThat(with("fake", 250, 50, 5, 0.15).enabled()).isTrue();
	}

	@Test
	void anUnknownProviderIsRefusedAtStartup() {
		assertThat(validator.validate(with("gemini", 250, 50, 5, 0.15))).isNotEmpty();
		assertThat(validator.validate(with("FAKE", 250, 50, 5, 0.15))).isNotEmpty();
	}

	@Test
	void theReserveCannotExceedTheBudget() {
		assertThat(validator.validate(with("fake", 100, 101, 5, 0.15))).isNotEmpty();
		assertThat(validator.validate(with("fake", 100, 100, 5, 0.15))).isEmpty();
		assertThat(validator.validate(with("fake", 100, 0, 5, 0.15))).isEmpty();
	}

	@Test
	void limitsAreBounded() {
		assertThat(validator.validate(with("fake", 0, 0, 5, 0.15))).isNotEmpty();
		assertThat(validator.validate(with("fake", 250, 50, 0, 0.15))).isNotEmpty();
		assertThat(validator.validate(with("fake", 250, 50, 21, 0.15))).isNotEmpty();
		assertThat(validator.validate(with("fake", 250, 50, 5, -0.1))).isNotEmpty();
		assertThat(validator.validate(with("fake", 250, 50, 5, 1.1))).isNotEmpty();
	}

}
