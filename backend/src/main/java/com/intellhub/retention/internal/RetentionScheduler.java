package com.intellhub.retention.internal;

import com.intellhub.retention.RetentionRunningException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the purge on a schedule inside the app. Off unless RETENTION_ENABLED=true, so a development run never
 * deletes data by surprise.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "intellhub.retention", name = "enabled", havingValue = "true")
class RetentionScheduler {

	private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

	private final RetentionService retention;

	RetentionScheduler(RetentionService retention) {
		this.retention = retention;
	}

	@Scheduled(cron = "${intellhub.retention.cron}")
	void runScheduled() {
		try {
			retention.run(false);
		}
		catch (RetentionRunningException e) {
			log.info("Skipping the scheduled purge: a run is already in progress");
		}
		catch (RuntimeException e) {
			// Never let an exception reach the scheduler thread; only the class is logged.
			log.warn("Scheduled purge failed: {}", e.getClass().getSimpleName());
		}
	}

}
