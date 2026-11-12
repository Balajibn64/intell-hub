package com.intellhub.enrichment;

/** The enrichment module's published API for the admin status page. Nothing here carries article text or keys. */
public interface EnrichmentStatus {

	EnrichmentSnapshot snapshot();

}
