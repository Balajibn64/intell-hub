package com.intellhub.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashesTests {

	@Test
	void matchesKnownSha256Vectors() {
		assertThat(Hashes.sha256Hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
		assertThat(Hashes.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void theByteAndHexFormsAgreeAndUseUtf8() {
		assertThat(Hashes.sha256("caf\u00e9")).hasSize(32);
		assertThat(Hashes.sha256Hex("caf\u00e9")).hasSize(64).matches("^[0-9a-f]{64}$");
		assertThat(Hashes.sha256Hex("caf\u00e9")).isNotEqualTo(Hashes.sha256Hex("cafe"));
	}

}
