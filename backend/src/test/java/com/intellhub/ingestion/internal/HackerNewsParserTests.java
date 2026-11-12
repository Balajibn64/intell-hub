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

class HackerNewsParserTests {

	private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

	private static final long RECENT = NOW.minusSeconds(3600).getEpochSecond();

	private final HackerNewsParser parser = new HackerNewsParser(JsonMapper.builder().build());

	private static SourceInfo source(Map<String, Object> config) {
		return new SourceInfo(UUID.randomUUID(), SourceType.HN, "HN", "https://hn.algolia.com/api/v1/search?tags=front_page", config);
	}

	private ParsedFeed parse(String json, int maxEntries, Map<String, Object> config) throws FeedParseException {
		return parser.parse(source(config), json.getBytes(StandardCharsets.UTF_8), new ParseLimits(maxEntries, NOW.minusSeconds(14 * 86400)));
	}

	private ParsedFeed parse(String json) throws FeedParseException {
		return parse(json, 100, Map.of());
	}

	private static String hit(String fields) {
		return "{" + fields + "}";
	}

	private static String hits(String... hits) {
		return "{\"hits\":[" + String.join(",", hits) + "],\"nbHits\":" + hits.length + "}";
	}

	private static final String STORY = hit("\"objectID\":\"50450488\",\"title\":\"Tim Peters has died\",\"url\":\"https://discuss.python.org/t/1\","
			+ "\"author\":\"ayhanfuat\",\"points\":312,\"num_comments\":85,\"created_at_i\":" + RECENT);

	@Test
	void aStoryBecomesAnEntryWithItsOutsideLinkTheTitleAndAPopularityLine() throws Exception {
		ParsedFeed feed = parse(hits(STORY));

		assertThat(feed.entries()).singleElement().satisfies(e -> {
			assertThat(e.link()).isEqualTo("https://discuss.python.org/t/1");
			assertThat(e.title()).isEqualTo("Tim Peters has died");
			assertThat(e.author()).isEqualTo("ayhanfuat");
			assertThat(e.publishedAt()).isEqualTo(Instant.ofEpochSecond(RECENT));
			assertThat(e.contentHtml()).isEqualTo("<p>312 points, 85 comments on Hacker News</p>");
		});
	}

	@Test
	void anAskHnPostUsesTheDiscussionPageAndKeepsItsBodyAsHtmlForTheSanitizer() throws Exception {
		ParsedFeed feed = parse(hits(hit("\"objectID\":\"777\",\"title\":\"Ask HN: What are you building?\",\"url\":null,\"points\":120,"
				+ "\"num_comments\":40,\"created_at_i\":" + RECENT + ",\"story_text\":\"<p>Tell us <script>x()</script></p>\"")));

		assertThat(feed.entries()).singleElement().satisfies(e -> {
			assertThat(e.link()).isEqualTo("https://news.ycombinator.com/item?id=777");
			assertThat(e.contentHtml()).startsWith("<p>120 points, 40 comments on Hacker News</p>").contains("Tell us");
		});
	}

	@Test
	void storiesBelowTheMinimumPointsAreLeftOutAndTheMinimumIsConfigurable() throws Exception {
		String low = hit("\"objectID\":\"1\",\"title\":\"Low\",\"url\":\"https://example.com/low\",\"points\":10,\"created_at_i\":" + RECENT);
		String missingPoints = hit("\"objectID\":\"2\",\"title\":\"None\",\"url\":\"https://example.com/none\",\"created_at_i\":" + RECENT);

		assertThat(parse(hits(STORY, low, missingPoints)).entries()).hasSize(1); // default minimum is 50
		assertThat(parse(hits(STORY, low, missingPoints), 100, Map.of("minPoints", 0)).entries()).hasSize(3);
		assertThat(parse(hits(STORY, low), 100, Map.of("minPoints", 500)).entries()).isEmpty();
		assertThat(parse(hits(STORY, low), 100, Map.of("minPoints", 5)).entries()).hasSize(2);
		assertThat(parse(hits(STORY, low)).skippedInvalid()).isEqualTo(1);
	}

	@Test
	void anythingThatIsNotTheExpectedDocumentFailsWithoutEchoingIt() {
		for (String bad : new String[] { "", "not json at all", "[]", "null", "{}", "{\"hits\":null}", "{\"hits\":{}}", "{\"hits\":\"x\"}",
				"{\"hits\":[", "<html>secret-page-text</html>" }) {
			assertThatThrownBy(() -> parse(bad)).as(bad).isInstanceOf(FeedParseException.class)
					.satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("secret-page-text"));
		}
	}

	@Test
	void wrongTypedOrMissingFieldsSkipThatHitOnlyAndNeverThrow() throws Exception {
		String[] bad = { hit("\"objectID\":50450488,\"title\":\"number id\",\"points\":100"), hit("\"title\":\"no id\",\"points\":100"),
				hit("\"objectID\":\"1\",\"title\":42,\"points\":100"), hit("\"objectID\":\"2\",\"title\":null,\"points\":100"),
				hit("\"objectID\":\"3\",\"title\":\"   \",\"points\":100"), hit("\"objectID\":\"4\",\"title\":{\"a\":1},\"points\":100"),
				hit("\"objectID\":\"abc\",\"title\":\"letters id\",\"points\":100"), hit("\"objectID\":\"1; drop table articles\",\"title\":\"sql id\",\"points\":100"),
				hit("\"objectID\":\"12345678901234\",\"title\":\"too long id\",\"points\":100"), "\"just a string\"", "42", "null", "[1,2]", "{}" };

		ParsedFeed feed = parse(hits(bad));

		assertThat(feed.entries()).isEmpty();
		assertThat(feed.skippedInvalid()).isEqualTo(bad.length);
	}

	@Test
	void pointsCommentsAndDatesOfTheWrongTypeCountAsMissingNotAsValues() throws Exception {
		ParsedFeed feed = parse(hits(hit("\"objectID\":\"9\",\"title\":\"T\",\"url\":\"https://example.com/t\",\"points\":\"900\"")), 100,
				Map.of("minPoints", 0));

		assertThat(feed.entries()).singleElement().satisfies(e -> {
			assertThat(e.contentHtml()).isEqualTo("<p>0 points, 0 comments on Hacker News</p>");
			assertThat(e.publishedAt()).isNull();
		});
		assertThat(parse(hits(STORY.replace("\"points\":312", "\"points\":3.5"))).entries()).isEmpty();
		assertThat(parse(hits(STORY.replace("\"points\":312", "\"points\":99999999999999999999999"))).entries()).isEmpty();
	}

	@Test
	void onlyHttpAndHttpsOutsideLinksAreKeptOthersFallBackToTheDiscussionPage() throws Exception {
		for (String link : new String[] { "javascript:alert(1)", "data:text/html,<script>x</script>", "ftp://example.com/x", "file:///etc/passwd",
				"//example.com/x", "example.com/x", "", " ", "https:" }) {
			ParsedFeed feed = parse(hits(hit("\"objectID\":\"31\",\"title\":\"T\",\"url\":\"" + link.replace("\"", "\\\"")
					+ "\",\"points\":100,\"created_at_i\":" + RECENT)));
			assertThat(feed.entries()).as(link).singleElement().satisfies(e -> assertThat(e.link()).isEqualTo("https://news.ycombinator.com/item?id=31"));
		}
		String tooLong = "https://example.com/" + "a".repeat(3000);
		assertThat(parse(hits(hit("\"objectID\":\"32\",\"title\":\"T\",\"url\":\"" + tooLong + "\",\"points\":100"))).entries().get(0).link())
				.isEqualTo("https://news.ycombinator.com/item?id=32");
	}

	@Test
	void textIsBoundedAndTheStatsLineCannotCarryMarkup() throws Exception {
		String huge = "x".repeat(100_000);
		ParsedFeed feed = parse(hits(hit("\"objectID\":\"41\",\"title\":\"" + huge + "\",\"author\":\"" + huge + "\",\"url\":\"https://example.com/h\","
				+ "\"points\":100,\"story_text\":\"" + huge + huge + "\"")));

		ParsedEntry e = feed.entries().get(0);
		assertThat(e.title().length()).isLessThanOrEqualTo(RomeFeedParser.MAX_TITLE_CHARS);
		assertThat(e.author().length()).isLessThanOrEqualTo(RomeFeedParser.MAX_AUTHOR_CHARS);
		assertThat(e.contentHtml().length()).isLessThanOrEqualTo(RomeFeedParser.MAX_CONTENT_CHARS + 100);
	}

	@Test
	void onlyTheConfiguredNumberOfEntriesAreTakenAndAHugeListIsNotWalked() throws Exception {
		String[] many = new String[1000];
		for (int i = 0; i < many.length; i++) {
			many[i] = hit("\"objectID\":\"" + (i + 1) + "\",\"title\":\"Story " + i + "\",\"url\":\"https://example.com/" + i + "\",\"points\":100,\"created_at_i\":" + RECENT);
		}

		assertThat(parse(hits(many), 7, Map.of()).entries()).hasSize(7);
		assertThat(parse(hits(many), 1000, Map.of()).entries()).hasSize(HackerNewsParser.MAX_HITS_READ);
	}

	@Test
	void datesAreCheckedOldOnesSkippedAndAbsurdOnesIgnored() throws Exception {
		long old = NOW.minusSeconds(30L * 86400).getEpochSecond();
		ParsedFeed oldFeed = parse(hits(STORY.replace(String.valueOf(RECENT), String.valueOf(old))));
		assertThat(oldFeed.entries()).isEmpty();
		assertThat(oldFeed.skippedOld()).isEqualTo(1);

		for (String date : new String[] { "-5", "99999999999999", "\"yesterday\"", "1.5", "null" }) {
			ParsedFeed feed = parse(hits(STORY.replace("\"created_at_i\":" + RECENT, "\"created_at_i\":" + date)));
			assertThat(feed.entries()).as(date).singleElement().satisfies(e -> assertThat(e.publishedAt()).isNull());
		}
	}

	@Test
	void theTitleIsPassedOnRawForTheSanitizerNotInterpretedHere() throws Exception {
		ParsedFeed feed = parse(hits(hit("\"objectID\":\"51\",\"title\":\"Using <form> & <script>x</script>\",\"url\":\"https://example.com/f\",\"points\":100")));

		assertThat(feed.entries().get(0).title()).isEqualTo("Using <form> & <script>x</script>");
	}

}
