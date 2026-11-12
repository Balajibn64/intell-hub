package com.intellhub.enrichment.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the worker on a timer, but only when a provider is configured: with none, nothing is scheduled at all. */
@Configuration
@EnableScheduling
@ConditionalOnExpression("!'${intellhub.enrichment.provider:}'.isBlank()")
class EnrichmentScheduler {

	private static final Logger log = LoggerFactory.getLogger(EnrichmentScheduler.class);

	private final EnrichmentWorker worker;

	EnrichmentScheduler(EnrichmentWorker worker) {
		this.worker = worker;
	}

	@Scheduled(initialDelayString = "${intellhub.enrichment.initial-delay:10s}",
			fixedDelayString = "${intellhub.enrichment.poll-interval:30s}")
	void runScheduled() {
		try {
			worker.tick();
		}
		catch (RuntimeException e) {
			// Never let an exception reach the scheduler thread; only the class is logged.
			log.warn("Enrichment run failed: {}", e.getClass().getSimpleName());
		}
	}

}
