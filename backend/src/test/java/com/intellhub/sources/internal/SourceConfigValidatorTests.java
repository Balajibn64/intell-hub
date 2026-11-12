package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceConfigValidatorTests {

	@Test
	void emptyAndModestConfigsPass() {
		assertThat(SourceConfigValidator.problem(Map.of(), 2)).isNull();
		assertThat(SourceConfigValidator.problem(Map.of("feed", "top", "tags", List.of("ai", "ml")), 60)).isNull();
	}

	@Test
	void configOverTheByteLimitFails() {
		assertThat(SourceConfigValidator.problem(Map.of(), SourceConfigValidator.MAX_BYTES + 1)).isNotNull();
		assertThat(SourceConfigValidator.problem(Map.of(), SourceConfigValidator.MAX_BYTES)).isNull();
	}

	@Test
	void nestingUpToTheLimitPassesAndBeyondFails() {
		// Four nested objects is the limit: top level plus three more.
		Map<String, Object> four = Map.of("a", Map.of("b", Map.of("c", Map.of("d", "leaf"))));
		Map<String, Object> five = Map.of("a", Map.of("b", Map.of("c", Map.of("d", Map.of("e", "leaf")))));
		Map<String, Object> listsCountToo = Map.of("a", List.of(List.of(List.of(List.of("x")))));

		assertThat(SourceConfigValidator.problem(four, 10)).isNull();
		assertThat(SourceConfigValidator.problem(five, 10)).isNotNull();
		assertThat(SourceConfigValidator.problem(listsCountToo, 10)).isNotNull();
	}

	@Test
	void keysMustBeNonBlankAndShort() {
		assertThat(SourceConfigValidator.problem(Map.of(" ", 1), 10)).isNotNull();
		assertThat(SourceConfigValidator.problem(Map.of("k".repeat(65), 1), 10)).isNotNull();
		assertThat(SourceConfigValidator.problem(Map.of("k".repeat(64), 1), 10)).isNull();
		assertThat(SourceConfigValidator.problem(Map.of("outer", Map.of("", 1)), 10)).isNotNull();
	}

}
