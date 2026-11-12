package com.intellhub.enrichment;

/**
 * One article sent to a provider. Only plain text goes out: the title and the stored excerpt.
 *
 * @param index the position in the batch; results are matched by this, never by an id a model could invent
 * @param title plain text
 * @param text plain text, already bounded
 */
public record SummaryItem(int index, String title, String text) {
}
