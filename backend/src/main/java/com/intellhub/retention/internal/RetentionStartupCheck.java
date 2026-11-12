package com.intellhub.retention.internal;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Stops startup if articles would be purged sooner than ingestion is willing to ignore them. Otherwise an
 * article of that age would be purged on one night and fetched and stored again on the next run, over and over.
 */
@Component
class RetentionStartupCheck implements InitializingBean {

	private final RetentionProperties properties;

	private final Environment environment;

	RetentionStartupCheck(RetentionProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		Duration ingestionMaxAge = Binder.get(environment).bind("intellhub.ingestion.max-age", Duration.class)
				.orElse(Duration.ofDays(7));
		verify(properties.window(), ingestionMaxAge);
	}

	static void verify(Duration window, Duration ingestionMaxAge) {
		if (window.compareTo(ingestionMaxAge) < 0) {
			throw new IllegalStateException("intellhub.retention.window (" + window + ") must not be shorter than "
					+ "intellhub.ingestion.max-age (" + ingestionMaxAge + "), or articles would be purged and fetched again");
		}
	}

}
