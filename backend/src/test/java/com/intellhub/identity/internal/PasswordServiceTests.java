package com.intellhub.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordServiceTests {

	private final PasswordService service = new PasswordService(TestProperties.defaults());

	@Test
	void hashIsNotThePasswordAndVerifies() {
		String hash = service.hash("correct horse battery");

		assertThat(hash).isNotEqualTo("correct horse battery").startsWith("$2");
		assertThat(service.matches("correct horse battery", hash)).isTrue();
		assertThat(service.matches("wrong password!!", hash)).isFalse();
	}

	@Test
	void samePasswordGetsADifferentHashEachTime() {
		assertThat(service.hash("same-password-123")).isNotEqualTo(service.hash("same-password-123"));
	}

	@Test
	void passwordsOverBcryptLimitAreRejected() {
		String tooLong = "a".repeat(PasswordService.MAX_PASSWORD_BYTES + 1);

		assertThatThrownBy(() -> service.hash(tooLong)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void multiByteCharactersCountAsBytes() {
		String tooLong = "é".repeat(37); // 74 bytes

		assertThatThrownBy(() -> service.hash(tooLong)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void burnDoesNotFailForAnyInput() {
		assertThatCode(() -> service.burn("whatever")).doesNotThrowAnyException();
	}

}
