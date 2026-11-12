package com.intellhub.ingestion.internal;

import java.time.Instant;

/**
 * @param maxEntries most entries taken from one feed
 * @param notBefore entries published before this are skipped; entries with no date are kept
 */
record ParseLimits(int maxEntries, Instant notBefore) {
}
