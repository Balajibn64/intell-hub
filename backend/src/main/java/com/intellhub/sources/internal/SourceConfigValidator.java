package com.intellhub.sources.internal;

import com.intellhub.shared.ApiException;
import com.intellhub.sources.SourceType;
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

	/** Most entries one fetch of one source may take (a busy feed cannot crowd out the rest). */
	static final int MAX_ENTRIES_LIMIT = 100;

	static final int MAX_MIN_POINTS = 1000;

	private SourceConfigValidator() {
	}

	/** Returns why the config is rejected, or null if it is acceptable. */
	static String problem(Map<String, Object> config, int serializedBytes) {
		if (serializedBytes > MAX_BYTES) {
			return "Config is too large";
		}
		return depthProblem(config, 1);
	}

	/** The bounds every source shares, then the type's own settings. */
	static void validate(SourceType type, Map<String, Object> config, int serializedBytes) {
		String problem = problem(config, serializedBytes);
		if (problem == null) {
			problem = typeProblem(type, config);
		}
		if (problem != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_CONFIG", problem);
		}
	}

	/** Whole numbers only (not text, not booleans, not fractions), inside the bounds. */
	static String typeProblem(SourceType type, Map<String, Object> config) {
		String problem = integerProblem(config, "maxEntries", 1, MAX_ENTRIES_LIMIT);
		if (problem == null && type == SourceType.HN) {
			problem = integerProblem(config, "minPoints", 0, MAX_MIN_POINTS);
		}
		return problem;
	}

	private static String integerProblem(Map<String, Object> config, String key, int min, int max) {
		Object value = config.get(key);
		if (value == null && !config.containsKey(key)) {
			return null;
		}
		boolean whole = value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
				|| value instanceof java.math.BigInteger;
		if (!whole || ((Number) value).longValue() < min || ((Number) value).longValue() > max) {
			return key + " must be a whole number from " + min + " to " + max;
		}
		return null;
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
