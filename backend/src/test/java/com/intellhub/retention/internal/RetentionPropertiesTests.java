package com.intellhub.retention.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RetentionPropertiesTests {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private static RetentionProperties with(Duration window, int batch, Duration grace) {
		return new RetentionProperties(false, "0 30 3 * * *", window, batch, grace);
	}

	private static RetentionProperties enabledWith(Duration window) {
		return new RetentionProperties(true, "0 30 3 * * *", window, 500, Duration.ofDays(1));
	}

	@Test
	void theDefaultsAreValid() {
		assertThat(validator.validate(with(Duration.ofDays(7), 500, Duration.ofDays(1)))).isEmpty();
	}

	@Test
	void theWindowMustBeBetweenOneAndThreeHundredSixtyFiveDays() {
		assertThat(validator.validate(with(Duration.ofDays(1), 500, Duration.ofDays(1)))).isEmpty();
		assertThat(validator.validate(with(Duration.ofDays(365), 500, Duration.ofDays(1)))).isEmpty();
		for (Duration bad : new Duration[] { Duration.ZERO, Duration.ofHours(23), Duration.ofDays(366), Duration.ofSeconds(-5) }) {
			assertThat(validator.validate(with(bad, 500, Duration.ofDays(1)))).as(bad.toString()).isNotEmpty();
		}
	}

	@Test
	void theBatchSizeAndTheGraceAreBounded() {
		assertThat(validator.validate(with(Duration.ofDays(7), 0, Duration.ofDays(1)))).isNotEmpty();
		assertThat(validator.validate(with(Duration.ofDays(7), 5001, Duration.ofDays(1)))).isNotEmpty();
		assertThat(validator.validate(with(Duration.ofDays(7), 5000, Duration.ZERO))).isEmpty();
		assertThat(validator.validate(with(Duration.ofDays(7), 500, Duration.ofHours(-1)))).isNotEmpty();
	}

	@Test
	void aWindowShorterThanTheIngestionAgeLimitStopsStartup() {
		assertThatThrownBy(() -> RetentionStartupCheck.verify(Duration.ofDays(3), Duration.ofDays(7)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("retention.window").hasMessageContaining("ingestion.max-age");
		RetentionStartupCheck.verify(Duration.ofDays(7), Duration.ofDays(7));
		RetentionStartupCheck.verify(Duration.ofDays(30), Duration.ofDays(7));
	}

	@Test
	void whenThePurgeIsOffAShortWindowOnlyWarnsAndStartupGoesOn() {
		RetentionProperties off = with(Duration.ofDays(3), 500, Duration.ofDays(1));

		new RetentionStartupCheck(off, new MockEnvironment()).afterPropertiesSet(); // no exception: nothing can purge by schedule
	}

	@Test
	void theCheckReadsTheIngestionSettingFromTheEnvironmentAndDefaultsToSevenDays() {
		RetentionProperties window5 = enabledWith(Duration.ofDays(5));

		assertThatThrownBy(() -> new RetentionStartupCheck(window5, new MockEnvironment()).afterPropertiesSet())
				.isInstanceOf(IllegalStateException.class); // 5 days against the 7-day default
		new RetentionStartupCheck(window5, new MockEnvironment().withProperty("intellhub.ingestion.max-age", "5d")).afterPropertiesSet();
		assertThatThrownBy(() -> new RetentionStartupCheck(window5, new MockEnvironment().withProperty("intellhub.ingestion.max-age", "14d")).afterPropertiesSet())
				.isInstanceOf(IllegalStateException.class);
	}

}
