package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HuggingFacePapersParserTests {

	private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

	private static final String RECENT = "2026-11-09T18:53:57.000Z";

	private final HuggingFacePapersParser parser = new HuggingFacePapersParser(JsonMapper.builder().build());

	private ParsedFeed parse(String json, int maxEntries) throws FeedParseException {
		SourceInfo source = new SourceInfo(UUID.randomUUID(), SourceType.HUGGINGFACE, "HF", "https://huggingface.co/api/daily_papers", Map.of());
		return parser.parse(source, json.getBytes(StandardCharsets.UTF_8), new ParseLimits(maxEntries, NOW.minusSeconds(14 * 86400)));
	}

	private ParsedFeed parse(String json) throws FeedParseException {
		return parse(json, 100);
	}

	private static String paper(String id, String paperFields) {
		return "{\"paper\":{\"id\":" + id + "," + paperFields + "},\"publishedAt\":\"" + RECENT + "\",\"title\":\"Item title\"}";
	}

	private static final String GOOD = paper("\"2611.12869\"", "\"title\":\"Where Does Alignment Emerge?\",\"summary\":\"The Platonic hypothesis suggests <b>models</b> converge.\","
			+ "\"authors\":[{\"_id\":\"x\",\"name\":\"Julia Herbst\",\"hidden\":false},{\"name\":\"Ada Lovelace\"}],\"publishedAt\":\"" + RECENT + "\",\"upvotes\":3");

	@Test
	void aPaperBecomesAnEntryWithABuiltLinkAuthorsAndEscapedSummary() throws Exception {
		ParsedFeed feed = parse("[" + GOOD + "]");

		assertThat(feed.entries()).singleElement().satisfies(e -> {
			assertThat(e.link()).isEqualTo("https://huggingface.co/papers/2611.12869");
			assertThat(e.title()).isEqualTo("Item title"); // the list item's own title comes first
			assertThat(e.author()).isEqualTo("Julia Herbst, Ada Lovelace");
			assertThat(e.publishedAt()).isEqualTo(Instant.parse(RECENT));
			assertThat(e.contentHtml()).isEqualTo("<p>The Platonic hypothesis suggests &lt;b&gt;models&lt;/b&gt; converge.</p><p>3 upvotes on Hugging Face</p>");
		});
	}

	@Test
	void theLinkIsBuiltFromAnArxivShapedIdOnlyNeverFromTheDocument() throws Exception {
		String[] badIds = { "\"../../admin\"", "\"2611.12869/../../x\"", "\"javascript:alert(1)\"", "\"https://evil.example/x\"", "\"abc\"",
				"\"2611.1\"", "\"2611.12869v123\"", "\"2611.12869 \"", "\"2611.12869\\n\"", "12345", "null", "\"\"" };
		StringBuilder items = new StringBuilder();
		for (String id : badIds) {
			items.append(items.length() == 0 ? "" : ",").append(paper(id, "\"title\":\"T\",\"summary\":\"s\""));
		}

		ParsedFeed feed = parse("[" + items + "]");

		assertThat(feed.entries()).isEmpty();
		assertThat(feed.skippedInvalid()).isEqualTo(badIds.length);
		assertThat(parse("[" + paper("\"2611.12869v2\"", "\"title\":\"T\"") + "]").entries().get(0).link()).isEqualTo("https://huggingface.co/papers/2611.12869v2");
	}

	@Test
	void anythingThatIsNotAnArrayFailsWithoutEchoingIt() {
		for (String bad : new String[] { "", "nope", "{}", "{\"hits\":[]}", "null", "\"x\"", "42", "[", "<html>secret-page-text</html>" }) {
			assertThatThrownBy(() -> parse(bad)).as(bad).isInstanceOf(FeedParseException.class)
					.satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("secret-page-text"));
		}
	}

	@Test
	void wrongTypedOrMissingFieldsSkipThatPaperOnlyAndNeverThrow() throws Exception {
		String[] bad = { "{}", "{\"paper\":null}", "{\"paper\":\"2611.12869\"}", "{\"paper\":[]}", "{\"paper\":{}}",
				"{\"paper\":{\"id\":\"2611.12869\"}}", "{\"paper\":{\"id\":\"2611.12869\",\"title\":42}}", "{\"paper\":{\"id\":\"2611.12869\",\"title\":\"  \"}}",
				"\"x\"", "5", "null", "[1]" };

		ParsedFeed feed = parse("[" + String.join(",", bad) + "]");

		assertThat(feed.entries()).isEmpty();
		assertThat(feed.skippedInvalid()).isEqualTo(bad.length);
	}

	@Test
	void optionalPartsAreIgnoredWhenTheyAreTheWrongShape() throws Exception {
		ParsedFeed feed = parse("[" + paper("\"2611.00001\"", "\"title\":\"T\",\"summary\":5,\"authors\":\"nobody\",\"upvotes\":\"many\",\"publishedAt\":\"not a date\"")
				.replace("\"publishedAt\":\"" + RECENT + "\",", "") + "]");

		assertThat(feed.entries()).singleElement().satisfies(e -> {
			assertThat(e.author()).isNull();
			assertThat(e.publishedAt()).isNull();
			assertThat(e.contentHtml()).isEmpty();
		});
		ParsedFeed oddAuthors = parse("[" + paper("\"2611.00002\"", "\"title\":\"T\",\"authors\":[null,5,{\"name\":7},{\"name\":\"Real Name\"},\"x\"]") + "]");
		assertThat(oddAuthors.entries().get(0).author()).isEqualTo("Real Name");
	}

	@Test
	void authorsAndTextAreBounded() throws Exception {
		StringBuilder authors = new StringBuilder();
		for (int i = 0; i < 50; i++) {
			authors.append(i == 0 ? "" : ",").append("{\"name\":\"Author").append(i).append("\"}");
		}
		String huge = "x".repeat(300_000);

		ParsedEntry e = parse("[" + paper("\"2611.00003\"", "\"title\":\"" + huge + "\",\"summary\":\"" + huge + "\",\"authors\":[" + authors + "]")
				.replace("\"title\":\"Item title\"", "\"title\":null") + "]").entries().get(0);

		assertThat(e.title().length()).isLessThanOrEqualTo(RomeFeedParser.MAX_TITLE_CHARS);
		assertThat(e.contentHtml().length()).isLessThanOrEqualTo(RomeFeedParser.MAX_CONTENT_CHARS + 100);
		assertThat(e.author()).contains("Author9").doesNotContain("Author10,");
	}

	@Test
	void onlyTheConfiguredNumberAreTakenAndAHugeListIsNotWalked() throws Exception {
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < 1000; i++) {
			items.append(i == 0 ? "" : ",").append(paper("\"2611." + String.format("%05d", i + 1) + "\"", "\"title\":\"T" + i + "\""));
		}

		assertThat(parse("[" + items + "]", 5).entries()).hasSize(5);
		assertThat(parse("[" + items + "]", 1000).entries()).hasSize(HuggingFacePapersParser.MAX_PAPERS_READ);
	}

	@Test
	void oldPapersAreSkippedAndAnUnreadableDateIsIgnored() throws Exception {
		ParsedFeed feed = parse("[" + paper("\"2610.00001\"", "\"title\":\"Old\"").replace(RECENT, "2026-09-01T00:00:00.000Z") + "]");

		assertThat(feed.entries()).isEmpty();
		assertThat(feed.skippedOld()).isEqualTo(1);
	}

}
