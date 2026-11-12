package com.intellhub.ingestion.internal;

import java.time.Instant;

/**
 * One feed entry as found in the feed. Nothing here is trusted: the HTML has not been sanitized and the
 * link has not been checked yet. Strings are only length-bounded.
 *
 * @param link the entry URL
 * @param title the title, possibly containing markup
 * @param author the author, or null
 * @param publishedAt publish or update time, or null when the feed gave none
 * @param contentHtml the content or summary as given, possibly containing arbitrary HTML, or an empty string
 */
record ParsedEntry(String link, String title, String author, Instant publishedAt, String contentHtml) {
}
