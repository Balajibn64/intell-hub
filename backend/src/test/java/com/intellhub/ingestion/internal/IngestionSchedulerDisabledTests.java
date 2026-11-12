package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class IngestionSchedulerDisabledTests {

	private final ApplicationContext context;

	IngestionSchedulerDisabledTests(ApplicationContext context) {
		this.context = context;
	}

	@Test
	void thereIsNoSchedulerByDefault() {
		assertThat(context.getBeansOfType(IngestionScheduler.class)).isEmpty();
	}

}
