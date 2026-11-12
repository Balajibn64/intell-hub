package com.intellhub.enrichment;

import java.util.List;

/**
 * What a provider returned for one item. It is untrusted until the validator has checked it: article text can
 * steer a model, so every field may be wrong, oversized or hostile.
 *
 * @param index the position of the item in the batch this answers
 */
public record SummaryResult(int index, String summary, String category, List<String> tags, double relevance) {
}
