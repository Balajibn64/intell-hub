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
 * Groq's OpenAI-compatible chat completions, asking for JSON that matches the answer schema (strict mode). All
 * knowledge of Groq's request and reply shape is in this class. It only moves data: the answer is checked by
 * the validator, never trusted here.
 */
final class GroqSummarizer implements SummarizerProvider {

	static final String NAME = "groq";

	/** Reasoning tokens count against the output limit, so reasoning models get more room and a low effort. */
	private static final int REASONING_MAX_TOKENS = 4000;

	private final ProviderHttp http;

	private final String apiKey;

	private final String model;

	private final SummaryPrompt prompt;

	private final ObjectMapper mapper;

	GroqSummarizer(ProviderHttp http, String apiKey, String model, SummaryPrompt prompt, ObjectMapper mapper) {
		this.http = http;
		this.apiKey = apiKey;
		this.model = model;
		this.prompt = prompt;
		this.mapper = mapper;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<SummaryResult> summarize(List<SummaryItem> batch) throws ProviderException {
		boolean reasoning = model.startsWith("openai/gpt-oss");
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("name", "article_summaries");
		schema.put("strict", true);
		schema.put("schema", prompt.schema());
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("model", model);
		request.put("messages", List.of(Map.of("role", "system", "content", prompt.system()),
				Map.of("role", "user", "content", prompt.user(batch))));
		request.put("temperature", 0.2);
		request.put("max_completion_tokens", reasoning ? REASONING_MAX_TOKENS : SummaryPrompt.MAX_OUTPUT_TOKENS);
		if (reasoning) {
			request.put("reasoning_effort", "low");
		}
		request.put("response_format", Map.of("type", "json_schema", "json_schema", schema));

		String reply = http.postJson(mapper.writeValueAsString(request), Map.of("Authorization", "Bearer " + apiKey));
		return prompt.parse(answerText(reply), batch.size());
	}

	/** The first choice's text; an answer cut off by the token limit is incomplete, so it fails. */
	private String answerText(String reply) throws ProviderException {
		try {
			JsonNode choice = mapper.readTree(reply).path("choices").path(0);
			JsonNode content = choice.path("message").path("content");
			if (!content.isString() || content.asString().isBlank() || "length".equals(choice.path("finish_reason").asString(""))) {
				throw new ProviderException(Kind.BAD_RESPONSE, "The provider returned no complete answer");
			}
			return content.asString();
		}
		catch (JacksonException e) {
			throw new ProviderException(Kind.BAD_RESPONSE, "The provider's reply was not valid JSON");
		}
	}

}
