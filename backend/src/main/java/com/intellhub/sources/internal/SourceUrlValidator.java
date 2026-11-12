package com.intellhub.sources.internal;

import com.intellhub.shared.ApiException;
import com.intellhub.shared.PublicUrlPolicy;
import com.intellhub.sources.SourceType;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Validates source URLs an administrator supplies, using the shared public-URL rules, and reports a
 * problem as a 400. The same rules are applied again at fetch time by the ingestion module.
 */
final class SourceUrlValidator {

	static final int MAX_LENGTH = PublicUrlPolicy.MAX_LENGTH;

	private SourceUrlValidator() {
	}

	/** The only address a Hacker News source may use (Algolia's HN API, the front page). */
	static final String HN_URL = "https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=30";

	/** The only address a Hugging Face source may use (the daily papers list). */
	static final String HUGGINGFACE_URL = "https://huggingface.co/api/daily_papers";

	private static final Pattern GITHUB_RELEASES = Pattern
			.compile("^https://github\\.com/[A-Za-z0-9_.-]{1,39}/[A-Za-z0-9_.-]{1,100}/releases\\.atom$");

	private static final Pattern ARXIV = Pattern.compile("^https://rss\\.arxiv\\.org/(rss|atom)/[A-Za-z]{1,12}(-[A-Za-z]{2,10})?(\\.[A-Za-z-]{1,15})?$");

	/** @throws ApiException with status 400 and code INVALID_SOURCE_URL when the URL is not acceptable */
	static void validate(String url) {
		String problem = problem(url);
		if (problem != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_URL", problem);
		}
	}

	/**
	 * The public-URL rules, and then the rules of the type: only RSS may point anywhere public; every other type
	 * has one fixed place it may fetch from, so an administrator (or a stolen admin token) cannot aim the server
	 * at an address of their choosing through a type that was meant for a specific service.
	 *
	 * @throws ApiException with status 400 and code INVALID_SOURCE_URL when the URL is not acceptable for the type
	 */
	static void validate(SourceType type, String url) {
		validate(url);
		String problem = typeProblem(type, PublicUrlPolicy.normalize(url));
		if (problem != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_URL", problem);
		}
	}

	/** Checks the normalized URL (lower-case scheme and host, no default port) against the type's address. */
	static String typeProblem(SourceType type, String normalizedUrl) {
		boolean ok = switch (type) {
			case RSS -> true;
			case HN -> HN_URL.equals(normalizedUrl);
			case HUGGINGFACE -> HUGGINGFACE_URL.equals(normalizedUrl);
			case GITHUB_RELEASES -> GITHUB_RELEASES.matcher(normalizedUrl).matches() && !hasDotOnlySegment(normalizedUrl);
			case ARXIV -> ARXIV.matcher(normalizedUrl).matches();
		};
		return ok ? null : "URL is not an allowed address for a " + type + " source";
	}

	/** "." and ".." are valid characters for the pattern but mean something else in a path. */
	private static boolean hasDotOnlySegment(String url) {
		for (String segment : url.substring("https://github.com/".length()).split("/")) {
			if (segment.chars().allMatch(c -> c == '.')) {
				return true;
			}
		}
		return false;
	}

	static String problem(String url) {
		return PublicUrlPolicy.problem(url);
	}

}
