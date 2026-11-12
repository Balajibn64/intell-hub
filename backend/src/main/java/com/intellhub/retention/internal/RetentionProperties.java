package com.intellhub.retention.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the purge of old articles.
 *
 * @param enabled run the purge on a schedule; off by default so a development run never deletes data by surprise
 * (an administrator can still run it by hand)
 * @param cron when the scheduled purge runs (Spring cron, six fields)
 * @param window articles published longer ago than this are purged, unless a user has them saved
 * @param batchSize articles handled per transaction
 * @param tokenGrace expired refresh tokens are deleted once they have been expired this long
 */
@ConfigurationProperties("intellhub.retention")
@Validated
record RetentionProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("0 30 3 * * *") @NotBlank String cron,
		@DefaultValue("7d") @NotNull Duration window,
		@DefaultValue("500") @Min(1) @Max(5000) int batchSize,
		@DefaultValue("1d") @NotNull Duration tokenGrace) {

	static final Duration MIN_WINDOW = Duration.ofDays(1);

	static final Duration MAX_WINDOW = Duration.ofDays(365);

	@AssertTrue(message = "retention window must be between 1 and 365 days")
	boolean isWindowReasonable() {
		return window == null || (window.compareTo(MIN_WINDOW) >= 0 && window.compareTo(MAX_WINDOW) <= 0);
	}

	@AssertTrue(message = "retention token-grace must not be negative")
	boolean isTokenGraceValid() {
		return tokenGrace == null || !tokenGrace.isNegative();
	}

}
