package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

/** Groq first, to show the order comes from the setting, in the configured order. The keys are fake and no call is made. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "intellhub.enrichment.provider=groq,gemini", "intellhub.enrichment.gemini.api-key=test-gemini",
		"intellhub.enrichment.groq.api-key=test-groq", "intellhub.enrichment.initial-delay=1h",
		"intellhub.enrichment.poll-interval=1h" })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class EnrichmentReversedProvidersWiringTests {

	private final ApplicationContext context;

	private final ProviderChain chain;

	EnrichmentReversedProvidersWiringTests(ApplicationContext context, ProviderChain chain) {
		this.context = context;
		this.chain = chain;
	}

	@Test
	void bothRealProvidersAreWiredInConfiguredOrderAndTheFakeIsNot() {
		assertThat(context.getBeansOfType(GeminiSummarizer.class)).hasSize(1);
		assertThat(context.getBeansOfType(GroqSummarizer.class)).hasSize(1);
		assertThat(context.getBeansOfType(FakeSummarizer.class)).isEmpty();
		assertThat(context.getBeansOfType(EnrichmentScheduler.class)).hasSize(1);
		assertThat(chain.providerNames()).containsExactly("groq", "gemini");
	}

}
