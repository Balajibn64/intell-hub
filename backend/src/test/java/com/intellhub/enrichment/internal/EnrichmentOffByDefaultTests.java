package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

/** With no provider set, nothing runs: no provider bean, no scheduler, an empty chain. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class EnrichmentOffByDefaultTests {

	private final ApplicationContext context;

	private final ProviderChain chain;

	EnrichmentOffByDefaultTests(ApplicationContext context, ProviderChain chain) {
		this.context = context;
		this.chain = chain;
	}

	@Test
	void enrichmentIsOffUntilAProviderIsConfigured() {
		assertThat(context.getBeansOfType(FakeSummarizer.class)).isEmpty();
		assertThat(context.getBeansOfType(EnrichmentScheduler.class)).isEmpty();
		assertThat(chain.hasProviders()).isFalse();
		assertThat(context.getBean(EnrichmentProperties.class).enabled()).isFalse();
	}

}
