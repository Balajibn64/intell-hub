package com.intellhub.ingestion.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for ingestion. The shared secret comes from an environment variable, never the repo.
 *
 * @param ingestSecret shared secret for POST /internal/ingest; when blank the endpoint refuses every call
 * @param scheduleEnabled run ingestion on a schedule inside the app (off by default)
 * @param cron schedule for the built-in runs
 * @param connectTimeout time allowed to open a connection to a source
 * @param responseTimeout time allowed to wait for data from a source
 * @param totalTimeout hard limit for fetching one source, including redirects
 * @param maxBodyBytes largest decoded feed accepted
 * @param maxRedirects most redirects followed per source
 * @param maxEntries most entries taken from one feed
 * @param maxAge entries older than this are not stored
 * @param concurrency how many sources are fetched at the same time
 * @param runTimeout limit for a whole run
 */
@ConfigurationProperties("intellhub.ingestion")
@Validated
record IngestionProperties(
		String ingestSecret,
		@DefaultValue("false") boolean scheduleEnabled,
		@DefaultValue("0 0 */4 * * *") @NotNull String cron,
		@DefaultValue("5s") @NotNull Duration connectTimeout,
		@DefaultValue("10s") @NotNull Duration responseTimeout,
		@DefaultValue("20s") @NotNull Duration totalTimeout,
		@DefaultValue("2097152") @Min(1024) long maxBodyBytes,
		@DefaultValue("3") @Min(0) @Max(10) int maxRedirects,
		@DefaultValue("100") @Min(1) @Max(1000) int maxEntries,
		@DefaultValue("14d") @NotNull Duration maxAge,
		@DefaultValue("4") @Min(1) @Max(16) int concurrency,
		@DefaultValue("3m") @NotNull Duration runTimeout) {

	static final int MIN_SECRET_LENGTH = 32;

	boolean secretConfigured() {
		return ingestSecret != null && !ingestSecret.isBlank();
	}

	@AssertTrue(message = "INGEST_SECRET must be at least 32 characters when set")
	boolean isSecretStrongEnough() {
		return !secretConfigured() || ingestSecret.length() >= MIN_SECRET_LENGTH;
	}

}
