package com.intellhub.enrichment.internal;

import com.intellhub.enrichment.SummarizerProvider;
import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.SummaryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A deterministic, offline stand-in for a language model: the summary is the opening of the text, tags and
 * category come from taxonomy keywords, and relevance is the keyword score. It makes no network call and is
 * only active when {@code intellhub.enrichment.provider=fake}. Results are labelled "fake" in the data so
 * they can never be mistaken for a real model's.
 */
@Component
@ConditionalOnProperty(prefix = "intellhub.enrichment", name = "provider", havingValue = "fake")
class FakeSummarizer implements SummarizerProvider {

	private static final int SUMMARY_CHARS = 300;

	private final Taxonomy taxonomy;

	FakeSummarizer(Taxonomy taxonomy) {
		this.taxonomy = taxonomy;
	}

	@Override
	public String name() {
		return "fake";
	}

	/** Its tags are keyword matches, not a model's choice. */
	@Override
	public String tagOrigin() {
		return "HEURISTIC";
	}

	@Override
	public List<SummaryResult> summarize(List<SummaryItem> batch) {
		List<SummaryResult> results = new ArrayList<>();
		for (SummaryItem item : batch) {
			Taxonomy.Matches matches = taxonomy.match(item.title(), item.text());
			List<String> tags = matches.hitsByTag().entrySet().stream()
					.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(EnrichmentValidator.MAX_TAGS)
					.map(Map.Entry::getKey).toList();
			results.add(new SummaryResult(item.index(), opening(item), taxonomy.categoryFor(matches), tags,
					taxonomy.score(item.title(), item.text())));
		}
		return results;
	}

	/** The first sentences of the text, up to a limit, or the title when there is no text. */
	private static String opening(SummaryItem item) {
		String text = item.text() == null ? "" : item.text().strip();
		if (text.isEmpty()) {
			return item.title() == null ? "" : item.title();
		}
		StringBuilder summary = new StringBuilder();
		for (String sentence : text.split("(?<=[.!?])\\s+")) {
			if (summary.length() > 0 && summary.length() + sentence.length() + 1 > SUMMARY_CHARS) {
				break;
			}
			summary.append(summary.length() == 0 ? "" : " ").append(sentence);
			if (summary.length() >= SUMMARY_CHARS / 2) {
				break;
			}
		}
		return summary.length() > SUMMARY_CHARS ? summary.substring(0, SUMMARY_CHARS) : summary.toString();
	}

}
