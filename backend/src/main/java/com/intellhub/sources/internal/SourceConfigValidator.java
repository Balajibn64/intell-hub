package com.intellhub.sources.internal;

import com.intellhub.shared.ApiException;
import java.util.Collection;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Bounds the free-form config object so a source cannot carry an unreasonable payload.
 * Type-specific rules are added together with each source adapter.
 */
final class SourceConfigValidator {

	static final int MAX_BYTES = 4096;

	static final int MAX_DEPTH = 4;

	static final int MAX_KEY_LENGTH = 64;

	private SourceConfigValidator() {
	}

	/** Returns why the config is rejected, or null if it is acceptable. */
	static String problem(Map<String, Object> config, int serializedBytes) {
		if (serializedBytes > MAX_BYTES) {
			return "Config is too large";
		}
		return depthProblem(config, 1);
	}

	static void validate(Map<String, Object> config, int serializedBytes) {
		String problem = problem(config, serializedBytes);
		if (problem != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_CONFIG", problem);
		}
	}

	private static String depthProblem(Object value, int depth) {
		if (value instanceof Map<?, ?> map) {
			if (depth > MAX_DEPTH) {
				return "Config is nested too deeply";
			}
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!(entry.getKey() instanceof String key) || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
					return "Config keys must be non-blank text of at most " + MAX_KEY_LENGTH + " characters";
				}
				String nested = depthProblem(entry.getValue(), depth + 1);
				if (nested != null) {
					return nested;
				}
			}
		}
		else if (value instanceof Collection<?> collection) {
			if (depth > MAX_DEPTH) {
				return "Config is nested too deeply";
			}
			for (Object item : collection) {
				String nested = depthProblem(item, depth + 1);
				if (nested != null) {
					return nested;
				}
			}
		}
		return null;
	}

}
