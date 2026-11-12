package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.ProviderException.Kind;
import com.intellhub.enrichment.SummaryItem;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The real provider classes in a chain against two local servers: Gemini fails, Groq answers. */
class RealProviderFallbackTests {

	private static final List<SummaryItem> BATCH = List.of(new SummaryItem(0, "Title", "Body"));

	private static final String GROQ_OK = "{\"choices\":[{\"message\":{\"content\":\"{\\\"items\\\":[{\\\"index\\\":0,\\\"summary\\\":\\\"From groq\\\",\\\"category\\\":\\\"AI_ML\\\",\\\"tags\\\":[],\\\"relevance\\\":0.5}]}\"},\"finish_reason\":\"stop\"}]}";

	private final ObjectMapper mapper = JsonMapper.builder().build();

	private final List<String> spent = new ArrayList<>();

	private final List<String> failed = new ArrayList<>();

	private StubProviderServer geminiServer;

	private StubProviderServer groqServer;

	private ProviderChain chain;

	@BeforeEach
	void start() throws Exception {
		geminiServer = new StubProviderServer();
		groqServer = new StubProviderServer();
		SummaryPrompt prompt = new SummaryPrompt(Set.of(), mapper);
		GeminiSummarizer gemini = new GeminiSummarizer(http(geminiServer), "gk", prompt, mapper);
		GroqSummarizer groq = new GroqSummarizer(http(groqServer), "qk", "openai/gpt-oss-20b", prompt, mapper);
		CallBudget budget = new CallBudget() {
			@Override
			public boolean tryReserve(BudgetPriority priority, String provider) {
				spent.add(provider);
				return true;
			}

			@Override
			public int remaining(BudgetPriority priority) {
				return 100;
			}

			@Override
			public void recordFailure(String provider) {
				failed.add(provider);
			}
		};
		chain = new ProviderChain(List.of(gemini, groq), budget);
	}

	private static ProviderHttp http(StubProviderServer server) {
		return new ProviderHttp(new ProviderHttp.Settings(URI.create(server.url("/x")), Duration.ofSeconds(2),
				Duration.ofSeconds(5), 64 * 1024));
	}

	@AfterEach
	void stop() {
		geminiServer.close();
		groqServer.close();
	}

	@Test
	void whenGeminiIsRateLimitedGroqAnswersAndBothAttemptsAreCounted() throws Exception {
		geminiServer.respond(429, "{}").header("retry-after", "30");
		groqServer.respond(200, GROQ_OK);

		ProviderChain.Answer answer = chain.summarize(BATCH, BudgetPriority.NORMAL);

		assertThat(answer.provider()).isEqualTo("groq");
		assertThat(answer.results()).singleElement().satisfies(r -> assertThat(r.summary()).isEqualTo("From groq"));
		assertThat(spent).containsExactly("gemini", "groq");
		assertThat(failed).containsExactly("gemini");
	}

	@Test
	void whenGeminiWorksGroqIsNeverCalled() throws Exception {
		geminiServer.respond(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"items\\\":[{\\\"index\\\":0,\\\"summary\\\":\\\"From gemini\\\",\\\"category\\\":\\\"AI_ML\\\",\\\"tags\\\":[],\\\"relevance\\\":0.5}]}\"}]}}]}");

		assertThat(chain.summarize(BATCH, BudgetPriority.NORMAL).provider()).isEqualTo("gemini");
		assertThat(groqServer.requests()).isZero();
	}

	@Test
	void whenBothFailTheLastErrorIsReportedWithItsRetryAfter() {
		geminiServer.respond(429, "{}").header("retry-after", "30");
		groqServer.respond(429, "{}").header("retry-after", "90");

		ProviderException e = catchFailure();

		assertThat(e.getKind()).isEqualTo(Kind.RATE_LIMITED);
		assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(90));
		assertThat(failed).containsExactly("gemini", "groq");
	}

	private ProviderException catchFailure() {
		try {
			chain.summarize(BATCH, BudgetPriority.NORMAL);
		}
		catch (ProviderException e) {
			return e;
		}
		catch (BudgetExhaustedException e) {
			throw new AssertionError(e);
		}
		throw new AssertionError("expected a ProviderException");
	}

}
