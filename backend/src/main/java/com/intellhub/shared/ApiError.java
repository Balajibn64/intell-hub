package com.intellhub.shared;

import java.util.Map;

/**
 * The one error body the API returns.
 *
 * @param code stable machine-readable code, for example {@code VALIDATION_FAILED}
 * @param message short human-readable text that never contains internals
 * @param details extra context such as per-field validation messages (never null)
 */
public record ApiError(String code, String message, Map<String, String> details) {

	public ApiError {
		details = details == null ? Map.of() : Map.copyOf(details);
	}

	public static ApiError of(String code, String message) {
		return new ApiError(code, message, Map.of());
	}

}
