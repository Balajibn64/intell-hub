package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

class IngestionSchedulingContextTests {

	/** The schedule is a bean only when switched on; the cron here never fires during a test. */
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest(properties = { "intellhub.ingestion.schedule-enabled=true", "intellhub.ingestion.cron=0 0 0 1 1 *" })
	@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
	static class Enabled {

		private final ApplicationContext context;

		Enabled(ApplicationContext context) {
			this.context = context;
		}

		@Test
		void theSchedulerExistsWhenEnabled() {
			assertThat(context.getBeansOfType(IngestionScheduler.class)).hasSize(1);
		}

	}

	@Import(TestcontainersConfiguration.class)
	@SpringBootTest
	@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
	static class ByDefault {

		private final ApplicationContext context;

		ByDefault(ApplicationContext context) {
			this.context = context;
		}

		@Test
		void thereIsNoSchedulerByDefault() {
			assertThat(context.getBeansOfType(IngestionScheduler.class)).isEmpty();
		}

	}

}
