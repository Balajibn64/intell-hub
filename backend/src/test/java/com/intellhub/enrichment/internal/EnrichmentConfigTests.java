package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EnrichmentConfigTests {

	private final EnrichmentConfig config = new EnrichmentConfig();

	private final SummaryPrompt prompt = new SummaryPrompt(java.util.Set.of("llm"), JsonMapper.builder().build());

	@Test
	void aListedProviderWithoutItsKeyStopsStartupNamingTheVariable() {
		assertThatThrownBy(() -> config.geminiSummarizer(new GeminiProperties("", "gemini-test", "https://example.com"), prompt,
				JsonMapper.builder().build())).isInstanceOf(IllegalStateException.class).hasMessageContaining("GEMINI_API_KEY");
		assertThatThrownBy(() -> config.groqSummarizer(new GroqProperties("  ", "m", "https://example.com"), prompt,
				JsonMapper.builder().build())).isInstanceOf(IllegalStateException.class).hasMessageContaining("GROQ_API_KEY");
	}

	@Test
	void theKeyIsNeverPartOfTheSettingsText() {
		String key = "super-secret-key-value";

		assertThat(new GeminiProperties(key, "gemini-test", "https://example.com").toString()).doesNotContain(key).contains("set");
		assertThat(new GroqProperties(key, "m", "https://example.com").toString()).doesNotContain(key).contains("set");
		assertThat(new GroqProperties("", "m", "https://example.com").toString()).contains("unset");
	}

	@Test
	void aProviderAddressMustBeHttps() {
		assertThatThrownBy(() -> config.geminiSummarizer(new GeminiProperties("k", "gemini-test", "http://example.com"), prompt,
				JsonMapper.builder().build())).isInstanceOf(IllegalArgumentException.class)
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain("k"));
	}

	@Test
	void theDefaultsPointAtTheRealServices() {
		assertThat(new GeminiProperties(null, "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com").model())
				.isEqualTo("gemini-3.5-flash-lite");
	}

	@Test
	void aKeyIsStrippedAndOneWithOddCharactersStopsStartup() throws Exception {
		assertThat(config.geminiSummarizer(new GeminiProperties("  abc123\r\n", "gemini-test", "https://example.com"), prompt,
				JsonMapper.builder().build())).isNotNull();
		assertThatThrownBy(() -> config.groqSummarizer(new GroqProperties("abc 123", "m", "https://example.com"), prompt,
				JsonMapper.builder().build())).isInstanceOf(IllegalStateException.class).hasMessageContaining("GROQ_API_KEY")
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain("abc"));
	}

	@Test
	void modelNamesCannotBeDotSegmentsOrStartWithPunctuation() {
		jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
		for (String bad : new String[] { "..", ".", ".hidden", "a b", "a?b", "a/b", "" }) {
			assertThat(validator.validate(new GeminiProperties("k", bad, "https://example.com"))).as(bad).isNotEmpty();
		}
		assertThat(validator.validate(new GeminiProperties("k", "gemini-3.5-flash-lite", "https://example.com"))).isEmpty();
		assertThat(validator.validate(new GroqProperties("k", "openai/gpt-oss-20b", "https://example.com"))).isEmpty();
		assertThat(validator.validate(new GroqProperties("k", "../x", "https://example.com"))).isNotEmpty();
	}

}
