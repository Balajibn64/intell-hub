package com.intellhub.shared;

/** Small text helpers shared by modules. */
public final class Text {

	private Text() {
	}

	/** Cuts to at most {@code max} characters without splitting a surrogate pair. Null stays null. */
	public static String cut(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		int end = max;
		if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(0, end);
	}

}
