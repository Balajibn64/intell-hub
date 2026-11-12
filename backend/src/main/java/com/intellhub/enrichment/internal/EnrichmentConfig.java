package com.intellhub.enrichment.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates the real providers named in {@code intellhub.enrichment.provider}. A listed provider without its API
 * key stops the application at startup, naming the variable but never a value. The fake provider is its own
 * component.
 */
@Configuration
@EnableConfigurationProperties({ EnrichmentProperties.class, GeminiProperties.class, GroqProperties.class })
class EnrichmentConfig {

	private static final String HAS_GEMINI = "(','+'${intellhub.enrichment.provider:}'+',').contains(',gemini,')";

	private static final String HAS_GROQ = "(','+'${intellhub.enrichment.provider:}'+',').contains(',groq,')";

	@Bean
	@ConditionalOnExpression(HAS_GEMINI + " or " + HAS_GROQ)
	SummaryPrompt summaryPrompt(Taxonomy taxonomy, ObjectMapper mapper) {
		return new SummaryPrompt(taxonomy.names(), mapper);
	}

	@Bean
	@ConditionalOnExpression(HAS_GEMINI)
	GeminiSummarizer geminiSummarizer(GeminiProperties settings, SummaryPrompt prompt, ObjectMapper mapper) {
		String key = requireKey(settings.apiKey(), "GEMINI_API_KEY", "gemini");
		ProviderHttp http = new ProviderHttp(ProviderHttp.Settings
				.of(trimSlash(settings.baseUrl()) + "/v1beta/models/" + settings.model() + ":generateContent"));
		return new GeminiSummarizer(http, key, prompt, mapper);
	}

	@Bean
	@ConditionalOnExpression(HAS_GROQ)
	GroqSummarizer groqSummarizer(GroqProperties settings, SummaryPrompt prompt, ObjectMapper mapper) {
		String key = requireKey(settings.apiKey(), "GROQ_API_KEY", "groq");
		ProviderHttp http = new ProviderHttp(
				ProviderHttp.Settings.of(trimSlash(settings.baseUrl()) + "/openai/v1/chat/completions"));
		return new GroqSummarizer(http, key, settings.model(), prompt, mapper);
	}

	/** Returns the key without stray whitespace; a key that is empty or has odd characters stops startup. */
	private static String requireKey(String key, String variable, String provider) {
		if (key == null || key.isBlank()) {
			throw new IllegalStateException(
					"Enrichment provider '" + provider + "' is enabled but " + variable + " is not set");
		}
		String stripped = key.strip();
		if (!stripped.matches("[\\x21-\\x7E]+")) {
			throw new IllegalStateException(variable + " contains characters that cannot be part of an API key");
		}
		return stripped;
	}

	private static String trimSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
