package com.intellhub.sources;

import java.util.List;
import java.util.UUID;

/** The sources module's published API for other modules, mainly ingestion. */
public interface SourceCatalog {

	/** All enabled sources, oldest first. */
	List<SourceInfo> enabledSources();

	/**
	 * Records the outcome of a fetch: the time, OK or FAILED, and the consecutive-failure count. An
	 * unknown id is ignored, since a source may be deleted while a run is in progress.
	 */
	void recordFetchResult(UUID sourceId, FetchResult result);

}
