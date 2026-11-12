package com.intellhub.ingestion.internal;

import com.intellhub.shared.Text;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Careful reading of a third party's JSON. Every accessor checks the type first and answers null for anything that
 * is not exactly what was expected, so a missing, null or wrong-typed field means "not usable" and never an
 * exception, a coerced value or a huge string.
 */
final class JsonFields {

	static final int MAX_LINK_CHARS = 2048;

	private JsonFields() {
	}

	/** A text field, cut to the limit; null if absent, not text or blank. */
	static String text(JsonNode node, String name, int max) {
		JsonNode value = node.get(name);
		if (value == null || !value.isString()) {
			return null;
		}
		String text = value.asString();
		return text.isBlank() ? null : Text.cut(text, max);
	}

	/** A whole number that fits in a long; null otherwise (text such as "5" is not accepted). */
	static Long whole(JsonNode node, String name) {
		JsonNode value = node.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
			return null;
		}
		return value.asLong();
	}

	/** An absolute http or https link of reasonable length, or null. Where it points is checked later. */
	static String httpLink(String candidate) {
		if (candidate == null) {
			return null;
		}
		String trimmed = candidate.strip();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		return (lower.startsWith("http://") || lower.startsWith("https://")) && trimmed.length() <= MAX_LINK_CHARS ? trimmed
				: null;
	}

}
