package com.intellhub.ingestion.internal;

import java.util.List;

/**
 * @param entries the usable entries, newest first as given by the feed, at most the configured maximum
 * @param skippedInvalid entries dropped for a missing or unusable link or title
 * @param skippedOld entries dropped for being older than the retention window
 */
record ParsedFeed(List<ParsedEntry> entries, int skippedInvalid, int skippedOld) {
}
