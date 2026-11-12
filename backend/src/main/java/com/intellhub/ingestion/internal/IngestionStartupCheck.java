package com.intellhub.ingestion.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Says clearly at startup when the ingestion endpoint is switched off for lack of a secret. */
@Component
class IngestionStartupCheck implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(IngestionStartupCheck.class);

	private final IngestionProperties properties;

	IngestionStartupCheck(IngestionProperties properties) {
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.secretConfigured()) {
			log.warn("INGEST_SECRET is not set: POST /internal/ingest refuses every call until it is");
		}
	}

}
