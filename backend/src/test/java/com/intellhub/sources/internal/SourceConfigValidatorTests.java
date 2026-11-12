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

	// ---- type settings ----

	private static String problem(com.intellhub.sources.SourceType type, java.util.Map<String, Object> config) {
		return SourceConfigValidator.typeProblem(type, config);
	}

	@Test
	void maxEntriesMustBeAWholeNumberFromOneToOneHundredForEveryType() {
		for (com.intellhub.sources.SourceType type : com.intellhub.sources.SourceType.values()) {
			assertThat(problem(type, java.util.Map.of("maxEntries", 1))).isNull();
			assertThat(problem(type, java.util.Map.of("maxEntries", 100))).isNull();
			assertThat(problem(type, java.util.Map.of("maxEntries", 25L))).isNull();
			assertThat(problem(type, java.util.Map.of())).isNull();
			for (Object bad : new Object[] { 0, 101, -5, 2.5, "25", true, java.util.List.of(1), java.util.Map.of(), Integer.MAX_VALUE,
					new java.math.BigInteger("18446744073709551621"), new java.math.BigInteger("5") }) {
				assertThat(problem(type, java.util.Map.of("maxEntries", bad))).as(String.valueOf(bad)).isNotNull();
			}
			java.util.Map<String, Object> withNull = new java.util.HashMap<>();
			withNull.put("maxEntries", null);
			assertThat(problem(type, withNull)).isNotNull();
		}
	}

	@Test
	void minPointsIsAHackerNewsSettingBoundedZeroToOneThousand() {
		assertThat(problem(com.intellhub.sources.SourceType.HN, java.util.Map.of("minPoints", 0))).isNull();
		assertThat(problem(com.intellhub.sources.SourceType.HN, java.util.Map.of("minPoints", 1000))).isNull();
		for (Object bad : new Object[] { -1, 1001, 50.5, "50", false }) {
			assertThat(problem(com.intellhub.sources.SourceType.HN, java.util.Map.of("minPoints", bad))).as(String.valueOf(bad)).isNotNull();
		}
		// Other types ignore it, as they ignore every setting that is not theirs.
		assertThat(problem(com.intellhub.sources.SourceType.RSS, java.util.Map.of("minPoints", "anything"))).isNull();
	}

	@Test
	void anInvalidTypeSettingIsABadRequestWithTheConfigCode() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> SourceConfigValidator.validate(com.intellhub.sources.SourceType.HN,
				java.util.Map.of("minPoints", -1), 20)).isInstanceOfSatisfying(com.intellhub.shared.ApiException.class,
				e -> assertThat(e.getCode()).isEqualTo("INVALID_SOURCE_CONFIG"));
	}

}
