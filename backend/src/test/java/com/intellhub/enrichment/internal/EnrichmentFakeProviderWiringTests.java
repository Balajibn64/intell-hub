package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

/** With the fake provider set, the provider and the scheduler exist; the long delays keep it from firing in a test. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "intellhub.enrichment.provider=fake", "intellhub.enrichment.initial-delay=1h",
		"intellhub.enrichment.poll-interval=1h" })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class EnrichmentFakeProviderWiringTests {

	private final ApplicationContext context;

	private final ProviderChain chain;

	EnrichmentFakeProviderWiringTests(ApplicationContext context, ProviderChain chain) {
		this.context = context;
		this.chain = chain;
	}

	@Test
	void theFakeProviderAndTheSchedulerAreWiredIn() {
		assertThat(context.getBeansOfType(FakeSummarizer.class)).hasSize(1);
		assertThat(context.getBeansOfType(EnrichmentScheduler.class)).hasSize(1);
		assertThat(chain.hasProviders()).isTrue();
		assertThat(context.getBean(EnrichmentProperties.class).enabled()).isTrue();
	}

}
