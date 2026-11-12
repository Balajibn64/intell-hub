package com.intellhub.enrichment.internal;

import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.SummaryResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The main security control of enrichment. Whatever a provider returns is untrusted, because the article
 * text it read can steer a language model. Nothing a model says is ever followed, fetched or executed: it is
 * reduced to a few bounded text fields, and anything that does not fit is dropped or the item is rejected.
 *
 * <p>Results are matched to the batch by position, ignoring out-of-range and repeated indexes, never by ids
 * the model supplies.
 */
@Component
class EnrichmentValidator {

	static final int MAX_SUMMARY_CHARS = 600;

	static final int MAX_TAGS = 5;

	static final Set<String> CATEGORIES = Set.of("AI_ML", "SOFTWARE_ENGINEERING", "INDUSTRY_STARTUPS");

	private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9+#.-]{0,29}$");

	private static final Pattern INVISIBLE = Pattern
			.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]");

	private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

	private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+|\\bwww\\.\\S+");

	record Accepted(String summary, String category, double relevance, List<String> tags) {
	}

	/** @param rejected indexes of sent items that got no usable result */
	record Outcome(Map<Integer, Accepted> accepted, Set<Integer> rejected) {
	}

	Outcome validate(List<SummaryItem> sent, List<SummaryResult> received) {
		Set<Integer> sentIndexes = new LinkedHashSet<>();
		sent.forEach(item -> sentIndexes.add(item.index()));
		Map<Integer, Accepted> accepted = new LinkedHashMap<>();
		Set<Integer> seen = new HashSet<>();
		if (received != null) {
			for (SummaryResult result : received) {
				if (result == null || !sentIndexes.contains(result.index()) || !seen.add(result.index())) {
					continue; // unknown or repeated index: ignore
				}
				Accepted checked = check(result);
				if (checked != null) {
					accepted.put(result.index(), checked);
				}
			}
		}
		Set<Integer> rejected = new LinkedHashSet<>(sentIndexes);
		rejected.removeAll(accepted.keySet());
		return new Outcome(accepted, rejected);
	}

	private Accepted check(SummaryResult result) {
		double relevance = result.relevance();
		if (!Double.isFinite(relevance) || relevance < 0.0 || relevance > 1.0) {
			return null;
		}
		String summary = cleanSummary(result.summary());
		if (summary.isEmpty()) {
			return null;
		}
		String category = result.category() != null && CATEGORIES.contains(result.category()) ? result.category() : null;
		return new Accepted(summary, category, relevance, cleanTags(result.tags()));
	}

	/**
	 * Plain text only: control, invisible and direction-override characters are removed, web addresses are
	 * dropped (so a hijacked summary cannot carry a link), spaces are collapsed and the length is bounded.
	 * Markup is not interpreted, and the text is only ever shown as text.
	 */
	static String cleanSummary(String raw) {
		if (raw == null) {
			return "";
		}
		String text = INVISIBLE.matcher(raw).replaceAll("");
		text = CONTROL.matcher(text).replaceAll(" ");
		text = URL.matcher(text).replaceAll(" ");
		text = text.replaceAll("\\s+", " ").strip();
		if (text.length() > MAX_SUMMARY_CHARS) {
			int end = MAX_SUMMARY_CHARS;
			if (Character.isHighSurrogate(text.charAt(end - 1))) {
				end--;
			}
			text = text.substring(0, end).stripTrailing();
		}
		return text;
	}

	static List<String> cleanTags(List<String> raw) {
		List<String> tags = new ArrayList<>();
		if (raw == null) {
			return tags;
		}
		Set<String> seen = new HashSet<>();
		for (String tag : raw) {
			if (tags.size() >= MAX_TAGS) {
				break;
			}
			if (tag == null) {
				continue;
			}
			String normalized = tag.strip().toLowerCase(Locale.ROOT);
			if (TAG.matcher(normalized).matches() && seen.add(normalized)) {
				tags.add(normalized);
			}
		}
		return tags;
	}

}
