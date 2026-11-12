package com.intellhub.enrichment.internal;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.ProviderException.Kind;
import com.intellhub.enrichment.SummarizerProvider;
import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.SummaryResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Gemini through the generateContent REST call, asking for JSON that matches the answer schema. All
 * knowledge of Gemini's request and reply shape is in this class. It only moves data: the answer is checked by
 * the validator, never trusted here.
 */
final class GeminiSummarizer implements SummarizerProvider {

	static final String NAME = "gemini";

	/** Thinking models spend part of this on thoughts, so it is well above what the answer itself needs. */
	static final int MAX_OUTPUT_TOKENS = 4000;

	private final ProviderHttp http;

	private final String apiKey;

	private final SummaryPrompt prompt;

	private final ObjectMapper mapper;

	GeminiSummarizer(ProviderHttp http, String apiKey, SummaryPrompt prompt, ObjectMapper mapper) {
		this.http = http;
		this.apiKey = apiKey;
		this.prompt = prompt;
		this.mapper = mapper;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<SummaryResult> summarize(List<SummaryItem> batch) throws ProviderException {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("responseMimeType", "application/json");
		config.put("responseJsonSchema", prompt.schema());
		config.put("temperature", 0.2);
		config.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("systemInstruction", Map.of("parts", List.of(Map.of("text", prompt.system()))));
		request.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt.user(batch))))));
		request.put("generationConfig", config);

		// The key goes in a header, never in the address, so it cannot end up in a log line or a proxy record.
		String reply = http.postJson(mapper.writeValueAsString(request), Map.of("x-goog-api-key", apiKey));
		return prompt.parse(answerText(reply), batch.size());
	}

	/** The text of the first candidate; a blocked or empty reply has none. */
	private String answerText(String reply) throws ProviderException {
		try {
			JsonNode candidate = mapper.readTree(reply).path("candidates").path(0);
			if ("MAX_TOKENS".equals(candidate.path("finishReason").asString(""))) {
				throw new ProviderException(Kind.BAD_RESPONSE, "The provider's answer was cut off");
			}
			JsonNode parts = candidate.path("content").path("parts");
			StringBuilder text = new StringBuilder();
			if (parts.isArray()) {
				for (JsonNode part : parts) {
					if (part.path("text").isString() && !part.path("thought").asBoolean(false)) {
						text.append(part.get("text").asString());
					}
				}
			}
			if (text.isEmpty()) {
				throw new ProviderException(Kind.BAD_RESPONSE, "The provider returned no answer");
			}
			return text.toString();
		}
		catch (JacksonException e) {
			throw new ProviderException(Kind.BAD_RESPONSE, "The provider's reply was not valid JSON");
		}
	}

}
