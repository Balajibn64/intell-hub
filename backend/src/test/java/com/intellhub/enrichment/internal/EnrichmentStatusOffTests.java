package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.enrichment.EnrichmentSnapshot;
import com.intellhub.enrichment.EnrichmentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

/** With no provider configured the status says so, and still shows the budget settings. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class EnrichmentStatusOffTests {

	private final EnrichmentStatus status;

	EnrichmentStatusOffTests(EnrichmentStatus status) {
		this.status = status;
	}

	@Test
	void enrichmentOffShowsNoProvidersAndTheDefaultBudget() {
		EnrichmentSnapshot s = status.snapshot();

		assertThat(s.enabled()).isFalse();
		assertThat(s.providers()).isEmpty();
		assertThat(s.dailyBudget()).isEqualTo(250);
		assertThat(s.reservedCalls()).isEqualTo(50);
	}

}
