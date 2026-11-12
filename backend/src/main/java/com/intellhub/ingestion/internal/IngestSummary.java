package com.intellhub.ingestion.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one ingestion run did. It holds counts, source ids and names, and fixed error texts only,
 * never anything taken from a feed.
 */
record IngestSummary(Instant startedAt, long durationMillis, int sourcesTotal, int sourcesOk, int sourcesFailed,
		int sourcesSkipped, int articlesCreated, int duplicates, int entriesSkipped, int entryErrors,
		List<SourceResult> sources) {

	enum Status {
		OK, FAILED, SKIPPED_UNSUPPORTED
	}

	record SourceResult(UUID sourceId, String name, Status status, String error, int entries, int created,
			int duplicates, int skipped, int errors) {

		static SourceResult failed(UUID id, String name, String error) {
			return new SourceResult(id, name, Status.FAILED, error, 0, 0, 0, 0, 0);
		}

		static SourceResult unsupported(UUID id, String name) {
			return new SourceResult(id, name, Status.SKIPPED_UNSUPPORTED, "Source type is not supported yet", 0, 0, 0, 0,
					0);
		}

	}

}
