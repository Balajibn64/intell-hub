package com.intellhub.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers, so no module repeats the checked-exception boilerplate. */
public final class Hashes {

	private Hashes() {
	}

	public static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	/** @return 64 lowercase hex characters */
	public static String sha256Hex(String value) {
		return HexFormat.of().formatHex(sha256(value));
	}

}
