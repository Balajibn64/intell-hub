package com.intellhub.ingestion.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers one ingestion run and waits for it. Called by a scheduler (the in-app one or an external cron)
 * with the shared secret; it is not part of the user-facing API.
 */
@RestController
@RequestMapping("/internal")
class IngestController {

	private final IngestionService ingestion;

	IngestController(IngestionService ingestion) {
		this.ingestion = ingestion;
	}

	/** Returns the run summary, or 409 if a run is already in progress. */
	@PostMapping("/ingest")
	IngestSummary ingest() {
		return ingestion.run();
	}

}
