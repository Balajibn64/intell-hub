package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.enrichment.EnrichmentSnapshot;
import com.intellhub.enrichment.EnrichmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/** The status with the fake provider on, a small budget, and some calls already made today. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "intellhub.enrichment.provider=fake", "intellhub.enrichment.daily-budget=100",
		"intellhub.enrichment.reserved-calls=20", "intellhub.enrichment.initial-delay=1h", "intellhub.enrichment.poll-interval=1h" })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class EnrichmentStatusServiceTests {

	private final EnrichmentStatus status;

	private final JdbcTemplate jdbc;

	EnrichmentStatusServiceTests(EnrichmentStatus status, JdbcTemplate jdbc) {
		this.status = status;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void clean() {
		jdbc.update("delete from llm_usage");
	}

	@Test
	void reportsTheConfigurationAndAnEmptyDayBeforeAnyCall() {
		EnrichmentSnapshot s = status.snapshot();

		assertThat(s.enabled()).isTrue();
		assertThat(s.providers()).containsExactly("fake");
		assertThat(s.dailyBudget()).isEqualTo(100);
		assertThat(s.reservedCalls()).isEqualTo(20);
		assertThat(s.usedToday()).isZero();
		assertThat(s.remainingForReaders()).isEqualTo(100);
		assertThat(s.remainingForBackground()).isEqualTo(80);
		assertThat(s.usageToday()).isEmpty();
		assertThat(s.day()).isNotNull();
	}

	@Test
	void countsTodaysCallsAndFailuresPerProviderAndShrinksTheRemainders() {
		java.time.LocalDate day = status.snapshot().day();
		jdbc.update("insert into llm_usage (day, provider, calls, failures) values (?, 'gemini', 60, 3), (?, 'groq', 25, 1)", day, day);
		jdbc.update("insert into llm_usage (day, provider, calls, failures) values (?, 'gemini', 999, 9)", day.minusDays(1)); // yesterday

		EnrichmentSnapshot s = status.snapshot();

		assertThat(s.usedToday()).isEqualTo(85);
		assertThat(s.remainingForReaders()).isEqualTo(15);
		assertThat(s.remainingForBackground()).isZero(); // 100 - 20 reserved - 85 is below zero
		assertThat(s.usageToday()).extracting(EnrichmentSnapshot.ProviderUsage::provider).containsExactly("gemini", "groq");
		assertThat(s.usageToday().get(0).calls()).isEqualTo(60);
		assertThat(s.usageToday().get(0).failures()).isEqualTo(3);
		assertThat(s.usageToday().get(1).calls()).isEqualTo(25);
	}

}
