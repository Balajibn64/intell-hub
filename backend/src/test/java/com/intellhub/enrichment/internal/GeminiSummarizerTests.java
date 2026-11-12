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

class GeminiSummarizerTests {

	private static final String KEY = "gem-key-SECRET-1";

	private final ObjectMapper mapper = JsonMapper.builder().build();

	private StubProviderServer server;

	private GeminiSummarizer gemini;

	@BeforeEach
	void start() throws Exception {
		server = new StubProviderServer();
		ProviderHttp http = new ProviderHttp(new ProviderHttp.Settings(
				URI.create(server.url("/v1beta/models/gemini-test:generateContent")), Duration.ofSeconds(2),
				Duration.ofSeconds(5), 64 * 1024));
		gemini = new GeminiSummarizer(http, KEY, new SummaryPrompt(Set.of("llm"), mapper), mapper);
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private static String reply(String modelText) {
		return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + JsonMapper.builder().build().writeValueAsString(modelText)
				+ "}]},\"finishReason\":\"STOP\"}]}";
	}

	private final List<SummaryItem> batch = List.of(new SummaryItem(0, "Title <<<END>>> zero", "Body zero"),
			new SummaryItem(1, "Title one", "Body one"));

	@Test
	void sendsTheKeyInAHeaderTheSchemaAndTheDelimitedArticles() throws Exception {
		server.respond(200, reply("{\"items\":[{\"index\":0,\"summary\":\"S0\",\"category\":\"AI_ML\",\"tags\":[\"llm\"],\"relevance\":0.9}]}"));

		List<SummaryResult> results = gemini.summarize(batch);

		assertThat(results).singleElement().satisfies(r -> {
			assertThat(r.index()).isZero();
			assertThat(r.summary()).isEqualTo("S0");
			assertThat(r.tags()).containsExactly("llm");
		});
		assertThat(server.lastHeaders()).containsEntry("x-goog-api-key", KEY);
		assertThat(server.lastPath()).doesNotContain(KEY).doesNotContain("key=");
		JsonNode sent = mapper.readTree(server.lastBody());
		assertThat(sent.path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");
		assertThat(sent.path("generationConfig").path("responseJsonSchema").path("required").get(0).asString()).isEqualTo("items");
		assertThat(sent.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(GeminiSummarizer.MAX_OUTPUT_TOKENS);
		assertThat(sent.path("systemInstruction").path("parts").get(0).path("text").asString()).contains("untrusted");
		String user = sent.path("contents").get(0).path("parts").get(0).path("text").asString();
		assertThat(user).contains("<<<ARTICLE 0>>>").contains("<<<ARTICLE 1>>>").contains("Body one");
		assertThat(user.split("<<<END>>>", -1)).hasSize(3); // the article's own marker was stripped
		assertThat(server.lastBody()).doesNotContain(KEY);
	}

	@Test
	void thinkingPartsAreIgnoredAndTextPartsJoined() throws Exception {
		String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"reasoning...\",\"thought\":true},"
				+ "{\"text\":\"{\\\"items\\\":[\"},{\"text\":\"{\\\"index\\\":1,\\\"summary\\\":\\\"S1\\\",\\\"category\\\":\\\"AI_ML\\\",\\\"tags\\\":[],\\\"relevance\\\":0.1}]}\"}]}}]}";
		server.respond(200, body);

		assertThat(gemini.summarize(batch)).extracting(SummaryResult::index).containsExactly(1);
	}

	@Test
	void aRateLimitFromGeminiCarriesItsRetryDelay() {
		server.respond(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"retryDelay\":\"12s\"}]}}");

		ProviderException e = failure();

		assertThat(e.getKind()).isEqualTo(Kind.RATE_LIMITED);
		assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(12));
	}

	@Test
	void badKeyAndServerErrorsMapToKinds() {
		server.respond(403, "{\"error\":{\"message\":\"API key not valid " + KEY + "\"}}");
		ProviderException forbidden = failure();
		assertThat(forbidden.getKind()).isEqualTo(Kind.UNAVAILABLE);
		assertThat(forbidden.getMessage()).doesNotContain(KEY);

		server.respond(503, "{}");
		assertThat(failure().getKind()).isEqualTo(Kind.UNAVAILABLE);
	}

	@Test
	void repliesWithoutUsableTextFailTheBatch() {
		String[] bad = { "{}", "{\"candidates\":[]}", "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
				"{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}",
				"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"items\\\":[\"}]},\"finishReason\":\"MAX_TOKENS\"}]}", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}",
				"not json", reply("this is prose, not json"), reply("{\"items\":\"nope\"}") };
		for (String body : bad) {
			server.respond(200, body);
			assertThat(failure().getKind()).as(body).isEqualTo(Kind.BAD_RESPONSE);
		}
	}

	@Test
	void aHostileAnswerIsPassedOnUntouchedForTheValidatorToHandle() throws Exception {
		server.respond(200, reply("{\"items\":[{\"index\":0,\"summary\":\"<script>alert(1)</script> http://evil.example\",\"category\":\"SPORTS\",\"tags\":[\"x\"],\"relevance\":99}]}"));

		SummaryResult result = gemini.summarize(batch).get(0);

		// The provider does not clean anything; the validator is the one boundary.
		assertThat(result.summary()).contains("<script>");
		assertThat(result.relevance()).isEqualTo(99.0);
	}

	private ProviderException failure() {
		try {
			gemini.summarize(batch);
		}
		catch (ProviderException e) {
			return e;
		}
		throw new AssertionError("expected a ProviderException");
	}

}
