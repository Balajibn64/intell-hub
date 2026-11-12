package com.intellhub.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceholdersTests {

	@Test
	void theExampleMarkerIsDetectedAnywhereAndInAnyCase() {
		assertThat(Placeholders.isPlaceholder("change-me")).isTrue();
		assertThat(Placeholders.isPlaceholder("change-me-to-a-strong-password")).isTrue();
		assertThat(Placeholders.isPlaceholder("xxx-CHANGE-ME-xxx")).isTrue();
	}

	@Test
	void realValuesAndNullAreNotPlaceholders() {
		assertThat(Placeholders.isPlaceholder("9f8e7d6c5b4a39281706f5e4d3c2b1a0")).isFalse();
		assertThat(Placeholders.isPlaceholder("change me")).isFalse();
		assertThat(Placeholders.isPlaceholder("")).isFalse();
		assertThat(Placeholders.isPlaceholder(null)).isFalse();
	}

}
