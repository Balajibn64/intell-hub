package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class EnrichmentSchedulerTests {

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
