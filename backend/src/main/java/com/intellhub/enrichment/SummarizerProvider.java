package com.intellhub.enrichment;

import java.util.List;

/**
 * Something that can summarize, tag and score a batch of articles. This is the only way business code reaches
 * a language model: never call a provider's API directly. Implementations rate-limit, time out and report
 * failures as {@link ProviderException}.
 */
public interface SummarizerProvider {

	/** A short stable name, stored with each result (for example {@code fake}). */
	String name();

	/**
	 * Processes one batch. May return fewer results than items, or malformed ones: the caller validates
	 * everything.
	 */
	List<SummaryResult> summarize(List<SummaryItem> batch) throws ProviderException;

}
