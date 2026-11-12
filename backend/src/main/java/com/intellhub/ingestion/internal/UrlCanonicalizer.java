package com.intellhub.ingestion.internal;

import com.intellhub.articles.NewArticle;
import com.intellhub.shared.PublicUrlPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Brings an article link to a canonical form, so the same article is recognised however it was linked:
 * lowercase scheme and host, no default port, no fragment, and no common tracking parameters. Other
 * query parameters are kept in their original order, and the path is never changed.
 */
final class UrlCanonicalizer {

	private static final Set<String> TRACKING = Set.of("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid",
			"igshid", "yclid", "_hsenc", "_hsmi");

	private UrlCanonicalizer() {
	}

	/** @return the canonical URL, or null if the link is not an acceptable public http(s) URL */
	static String canonicalize(String link) {
		if (link == null || PublicUrlPolicy.problem(link) != null) {
			return null;
		}
		String normalized = PublicUrlPolicy.normalize(link);
		int hash = normalized.indexOf('#');
		if (hash >= 0) {
			normalized = normalized.substring(0, hash);
		}
		int question = normalized.indexOf('?');
		if (question >= 0) {
			String base = normalized.substring(0, question);
			String query = normalized.substring(question + 1);
			List<String> kept = new ArrayList<>();
			for (String pair : query.split("&")) {
				if (!pair.isEmpty() && !isTracking(pair)) {
					kept.add(pair);
				}
			}
			normalized = kept.isEmpty() ? base : base + "?" + String.join("&", kept);
		}
		return normalized.length() <= NewArticle.MAX_URL ? normalized : null;
	}

	private static boolean isTracking(String pair) {
		int equals = pair.indexOf('=');
		String name = (equals >= 0 ? pair.substring(0, equals) : pair).toLowerCase(Locale.ROOT);
		return name.startsWith("utm_") || TRACKING.contains(name);
	}

}
