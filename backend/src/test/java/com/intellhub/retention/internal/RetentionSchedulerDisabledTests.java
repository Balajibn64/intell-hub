package com.intellhub.retention.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

/** By default nothing is scheduled: a development run never deletes data by surprise. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RetentionSchedulerDisabledTests {

	private final ApplicationContext context;

	RetentionSchedulerDisabledTests(ApplicationContext context) {
		this.context = context;
	}

	@Test
	void noSchedulerExistsUnlessSwitchedOnButTheServiceAndDefaultsDo() {
		assertThat(context.getBeansOfType(RetentionScheduler.class)).isEmpty();
		assertThat(context.getBeansOfType(RetentionService.class)).hasSize(1);
		RetentionProperties properties = context.getBean(RetentionProperties.class);
		assertThat(properties.enabled()).isFalse();
		assertThat(properties.window().toDays()).isEqualTo(7);
		assertThat(properties.batchSize()).isEqualTo(500);
	}

}
