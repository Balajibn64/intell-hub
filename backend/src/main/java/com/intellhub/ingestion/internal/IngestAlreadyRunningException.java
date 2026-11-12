package com.intellhub.ingestion.internal;

import com.intellhub.shared.ApiException;
import org.springframework.http.HttpStatus;

/** Another ingestion run is still in progress. */
class IngestAlreadyRunningException extends ApiException {

	IngestAlreadyRunningException() {
		super(HttpStatus.CONFLICT, "INGEST_RUNNING", "An ingestion run is already in progress");
	}

}
