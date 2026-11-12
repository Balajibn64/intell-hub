package com.intellhub.shared;

import java.util.Locale;

/**
 * Detects the example values shipped in .env.example. A secret that still says "change-me" is public
 * knowledge, so the app refuses to start with one instead of running with a known key.
 */
public final class Placeholders {

	private static final String MARKER = "change-me";

	private Placeholders() {
	}

	public static boolean isPlaceholder(String value) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(MARKER);
	}

}
