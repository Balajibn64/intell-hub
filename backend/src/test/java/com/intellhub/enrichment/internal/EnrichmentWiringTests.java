package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.intellhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

class EnrichmentWiringTests {

	/** With no provider set, nothing runs: no provider bean, no scheduler, an empty chain. */
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
	static class ByDefault {

		private final ApplicationContext context;

		private final ProviderChain chain;

		ByDefault(ApplicationContext context, ProviderChain chain) {
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

	/** With the fake provider set, the provider and the scheduler exist; the long delay keeps it from firing in a test. */
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest(properties = { "intellhub.enrichment.provider=fake", "intellhub.enrichment.initial-delay=1h",
			"intellhub.enrichment.poll-interval=1h" })
	@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
	static class WithTheFakeProvider {

		private final ApplicationContext context;

		private final ProviderChain chain;

		WithTheFakeProvider(ApplicationContext context, ProviderChain chain) {
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

	static class SchedulerBehaviour {

		private final EnrichmentWorker worker = mock(EnrichmentWorker.class);

		private final EnrichmentScheduler scheduler = new EnrichmentScheduler(worker);

		@Test
		void aScheduledTickRunsTheWorkerOnce() {
			scheduler.runScheduled();

			verify(worker, times(1)).tick();
		}

		@Test
		void noExceptionEverReachesTheSchedulerThread() {
			doThrow(new IllegalStateException("boom with article text")).when(worker).tick();

			assertThatCode(scheduler::runScheduled).doesNotThrowAnyException();
		}

	}

}
