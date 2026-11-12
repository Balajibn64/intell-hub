package com.intellhub.sources.internal;

import com.intellhub.shared.ApiException;
import com.intellhub.shared.PublicUrlPolicy;
import org.springframework.http.HttpStatus;

/**
 * Validates source URLs an administrator supplies, using the shared public-URL rules, and reports a
 * problem as a 400. The same rules are applied again at fetch time by the ingestion module.
 */
final class SourceUrlValidator {

	static final int MAX_LENGTH = PublicUrlPolicy.MAX_LENGTH;

	private SourceUrlValidator() {
	}

	/** @throws ApiException with status 400 and code INVALID_SOURCE_URL when the URL is not acceptable */
	static void validate(String url) {
		String problem = problem(url);
		if (problem != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_URL", problem);
		}
	}

	static String problem(String url) {
		return PublicUrlPolicy.problem(url);
	}

}
