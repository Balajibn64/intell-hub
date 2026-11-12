package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.shared.ApiException;
import org.junit.jupiter.api.Test;

/** The detailed URL rules are tested in the shared PublicUrlPolicyTests; this covers the wrapper. */
class SourceUrlValidatorTests {

	@Test
	void validateThrowsABadRequestApiException() {
		assertThatThrownBy(() -> SourceUrlValidator.validate("http://127.0.0.1/"))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus().value()).isEqualTo(400);
					assertThat(e.getCode()).isEqualTo("INVALID_SOURCE_URL");
				});
		SourceUrlValidator.validate("https://example.com/feed");
	}

	@Test
	void usesTheSharedRulesAndLength() {
		assertThat(SourceUrlValidator.problem("http://169.254.169.254/")).isNotNull();
		assertThat(SourceUrlValidator.problem("https://example.com/feed")).isNull();
		assertThat(SourceUrlValidator.MAX_LENGTH).isEqualTo(2048);
	}

}
