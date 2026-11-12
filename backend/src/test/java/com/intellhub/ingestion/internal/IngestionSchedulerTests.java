package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class IngestionSchedulerTests {

	private final IngestionService ingestion = mock(IngestionService.class);

	private final IngestionScheduler scheduler = new IngestionScheduler(ingestion);

	@Test
	void aScheduledTickRunsIngestionOnce() {
		scheduler.runScheduled();

		verify(ingestion, times(1)).run();
	}

	@Test
	void aTickDuringAnotherRunIsSkippedQuietly() {
		doThrow(new IngestAlreadyRunningException()).when(ingestion).run();

		assertThatCode(scheduler::runScheduled).doesNotThrowAnyException();
	}

	@Test
	void noExceptionEverReachesTheSchedulerThread() {
		doThrow(new IllegalStateException("boom with feed text")).when(ingestion).run();

		assertThatCode(scheduler::runScheduled).doesNotThrowAnyException();
	}

}
