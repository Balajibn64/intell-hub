package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ParserRegistryTests {

	private static SourceParser parser(SourceType... types) {
		return new SourceParser() {
			@Override
			public Set<SourceType> types() {
				return Set.of(types);
			}

			@Override
			public ParsedFeed parse(SourceInfo source, byte[] body, ParseLimits limits) {
				return new ParsedFeed(List.of(), 0, 0);
			}
		};
	}

	@Test
	void everyTypeResolvesToItsOwnParserAndAnUnclaimedTypeToNone() {
		SourceParser feeds = parser(SourceType.RSS, SourceType.ARXIV);
		SourceParser hn = parser(SourceType.HN);

		ParserRegistry registry = new ParserRegistry(List.of(feeds, hn));

		assertThat(registry.forType(SourceType.RSS)).isSameAs(feeds);
		assertThat(registry.forType(SourceType.ARXIV)).isSameAs(feeds);
		assertThat(registry.forType(SourceType.HN)).isSameAs(hn);
		assertThat(registry.forType(SourceType.HUGGINGFACE)).isNull();
	}

	@Test
	void twoParsersForOneTypeStopStartup() {
		assertThatThrownBy(() -> new ParserRegistry(List.of(parser(SourceType.HN), parser(SourceType.HN, SourceType.RSS))))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("HN");
	}

	@Test
	void theRealParsersCoverEveryTypeExactlyOnce() {
		ParserRegistry registry = new ParserRegistry(List.of(new RssSourceParser(new RomeFeedParser()),
				new HackerNewsParser(JsonMapper.builder().build()), new HuggingFacePapersParser(JsonMapper.builder().build())));

		for (SourceType type : SourceType.values()) {
			assertThat(registry.forType(type)).as(type.name()).isNotNull();
		}
	}

}
