package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.ProviderException.Kind;
import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.SummaryResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class GroqSummarizerTests {

	private static final String KEY = "gsk_SECRET_key_1";

	private final ObjectMapper mapper = JsonMapper.builder().build();

	private StubProviderServer server;

	private ProviderHttp http;

	@BeforeEach
	void start() throws Exception {
		server = new StubProviderServer();
		http = new ProviderHttp(new ProviderHttp.Settings(URI.create(server.url("/openai/v1/chat/completions")),
				Duration.ofSeconds(2), Duration.ofSeconds(5), 64 * 1024));
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private GroqSummarizer groq(String model) {
		return new GroqSummarizer(http, KEY, model, new SummaryPrompt(Set.of("llm"), mapper), mapper);
	}

	private String reply(String content, String finish) {
		return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + mapper.writeValueAsString(content)
				+ "},\"finish_reason\":\"" + finish + "\"}]}";
	}

	private final List<SummaryItem> batch = List.of(new SummaryItem(0, "Title", "Body"));

	private static final String GOOD = "{\"items\":[{\"index\":0,\"summary\":\"S0\",\"category\":\"AI_ML\",\"tags\":[\"llm\"],\"relevance\":0.7}]}";

	@Test
	void sendsTheKeyAsABearerHeaderAndAStrictSchemaRequest() throws Exception {
		server.respond(200, reply(GOOD, "stop"));

		List<SummaryResult> results = groq("openai/gpt-oss-20b").summarize(batch);

		assertThat(results).singleElement().satisfies(r -> assertThat(r.summary()).isEqualTo("S0"));
		assertThat(server.lastHeaders()).containsEntry("authorization", "Bearer " + KEY);
		assertThat(server.lastPath()).doesNotContain(KEY);
		JsonNode sent = mapper.readTree(server.lastBody());
		assertThat(sent.path("model").asString()).isEqualTo("openai/gpt-oss-20b");
		assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_schema");
		assertThat(sent.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
		assertThat(sent.path("reasoning_effort").asString()).isEqualTo("low");
		assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("system");
		assertThat(sent.path("messages").get(1).path("content").asString()).contains("<<<ARTICLE 0>>>").contains("Body");
		assertThat(server.lastBody()).doesNotContain(KEY);
	}

	@Test
	void reasoningEffortIsOnlySentToReasoningModels() throws Exception {
		server.respond(200, reply(GOOD, "stop"));

		groq("qwen/qwen3.8-27b").summarize(batch);

		JsonNode sent = mapper.readTree(server.lastBody());
		assertThat(sent.has("reasoning_effort")).isFalse();
		assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(SummaryPrompt.MAX_OUTPUT_TOKENS);
	}

	@Test
	void aRateLimitCarriesTheRetryAfterHeader() {
		server.respond(429, "{\"error\":{\"message\":\"slow down\"}}").header("retry-after", "2");

		ProviderException e = failure(groq("openai/gpt-oss-20b"));

		assertThat(e.getKind()).isEqualTo(Kind.RATE_LIMITED);
		assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(2));
	}

	@Test
	void badKeyAndServerErrorsMapToKindsWithoutLeakingTheKey() {
		server.respond(401, "{\"error\":{\"message\":\"Invalid API Key " + KEY + "\"}}");
		ProviderException unauthorized = failure(groq("m"));
		assertThat(unauthorized.getKind()).isEqualTo(Kind.UNAVAILABLE);
		assertThat(unauthorized.getMessage()).doesNotContain(KEY);

		server.respond(500, "{}");
		assertThat(failure(groq("m")).getKind()).isEqualTo(Kind.UNAVAILABLE);
	}

	@Test
	void incompleteOrEmptyAnswersFailTheBatch() {
		String[] bad = { "{}", "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":null}}]}",
				reply("", "stop"), reply(GOOD, "length"), "not json", reply("prose, not json", "stop"),
				reply("{\"items\":{}}", "stop") };
		for (String body : bad) {
			server.respond(200, body);
			assertThat(failure(groq("m")).getKind()).as(body).isEqualTo(Kind.BAD_RESPONSE);
		}
	}

	private ProviderException failure(GroqSummarizer groq) {
		try {
			groq.summarize(batch);
		}
		catch (ProviderException e) {
			return e;
		}
		throw new AssertionError("expected a ProviderException");
	}

}
