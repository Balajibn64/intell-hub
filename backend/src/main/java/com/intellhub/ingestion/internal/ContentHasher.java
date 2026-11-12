package com.intellhub.ingestion.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fingerprints an article so the same one under a different URL (a mirror, a syndicated copy) is skipped.
 *
 * <p>With enough text the hash covers the title and the start of the text. With little or no text it
 * covers the canonical URL as well, so unrelated short items with the same title are never merged.
 */
final class ContentHasher {

	static final int MIN_TEXT_FOR_CONTENT_HASH = 200;

	static final int TEXT_CHARS_HASHED = 2000;

	private ContentHasher() {
	}

	/** @return 64 lowercase hex characters */
	static String hash(String plainTitle, String plainText, String canonicalUrl) {
		String title = normalize(plainTitle);
		String text = normalize(plainText);
		String input;
		if (text.length() >= MIN_TEXT_FOR_CONTENT_HASH) {
			input = title + "\n" + text.substring(0, Math.min(text.length(), TEXT_CHARS_HASHED));
		}
		else {
			input = canonicalUrl + "\n" + title + "\n" + text;
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

}
