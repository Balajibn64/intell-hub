package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.ProviderException.Kind;
import com.intellhub.enrichment.SummarizerProvider;
import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.SummaryResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderChainTests {

	private static final List<SummaryItem> BATCH = List.of(new SummaryItem(0, "t", "x"));

	private final List<String> calls = new ArrayList<>();

	private SummarizerProvider working(String name) {
		return new SummarizerProvider() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public List<SummaryResult> summarize(List<SummaryItem> batch) {
				calls.add(name);
				return List.of(new SummaryResult(0, name + " summary", null, List.of(), 0.5));
			}
		};
	}

	private SummarizerProvider failing(String name, Kind kind) {
		return new SummarizerProvider() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public List<SummaryResult> summarize(List<SummaryItem> batch) throws ProviderException {
				calls.add(name);
				throw new ProviderException(kind, name + " failed", Duration.ofSeconds(7));
			}
		};
	}

	@Test
	void anEmptyChainIsNotConfiguredAndReportsUnavailable() {
		ProviderChain chain = new ProviderChain(List.of());

		assertThat(chain.hasProviders()).isFalse();
		assertThatThrownBy(() -> chain.summarize(BATCH)).isInstanceOfSatisfying(ProviderException.class,
				e -> assertThat(e.getKind()).isEqualTo(Kind.UNAVAILABLE));
	}

	@Test
	void theFirstWorkingProviderIsUsedAndTheRestAreNotCalled() throws Exception {
		ProviderChain chain = new ProviderChain(List.of(working("first"), working("second")));

		ProviderChain.Answer answer = chain.summarize(BATCH);

		assertThat(answer.provider()).isEqualTo("first");
		assertThat(answer.results().get(0).summary()).isEqualTo("first summary");
		assertThat(calls).containsExactly("first");
	}

	@Test
	void aFailingProviderFallsThroughToTheNextAndTheAnswerNamesWhoAnswered() throws Exception {
		ProviderChain chain = new ProviderChain(List.of(failing("limited", Kind.RATE_LIMITED), failing("down", Kind.UNAVAILABLE),
				working("backup")));

		ProviderChain.Answer answer = chain.summarize(BATCH);

		assertThat(answer.provider()).isEqualTo("backup");
		assertThat(calls).containsExactly("limited", "down", "backup");
	}

	@Test
	void whenEveryProviderFailsTheLastErrorIsReported() {
		ProviderChain chain = new ProviderChain(List.of(failing("a", Kind.RATE_LIMITED), failing("b", Kind.TIMEOUT)));

		assertThatThrownBy(() -> chain.summarize(BATCH)).isInstanceOfSatisfying(ProviderException.class, e -> {
			assertThat(e.getKind()).isEqualTo(Kind.TIMEOUT);
			assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(7));
			assertThat(e.getMessage()).doesNotContain("t").isEqualTo("b failed");
		});
		assertThat(calls).containsExactly("a", "b");
	}

}
