package com.intellhub.ingestion.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs ingestion on a schedule inside the app. Off unless INGEST_SCHEDULE_ENABLED=true, so a development
 * run never reaches out to the network by surprise. An external cron can call POST /internal/ingest instead.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "intellhub.ingestion", name = "schedule-enabled", havingValue = "true")
class IngestionScheduler {

	private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

	private final IngestionService ingestion;

	IngestionScheduler(IngestionService ingestion) {
		this.ingestion = ingestion;
	}

	@Scheduled(cron = "${intellhub.ingestion.cron}")
	void runScheduled() {
		try {
			ingestion.run();
		}
		catch (IngestAlreadyRunningException e) {
			log.info("Skipping the scheduled ingestion: a run is already in progress");
		}
		catch (RuntimeException e) {
			// Never let an exception reach the scheduler thread; only the class is logged.
			log.warn("Scheduled ingestion failed: {}", e.getClass().getSimpleName());
		}
	}

}
