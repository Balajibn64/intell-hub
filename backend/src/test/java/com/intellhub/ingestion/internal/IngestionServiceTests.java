package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.ingestion.internal.IngestSummary.SourceResult;
import com.intellhub.ingestion.internal.IngestSummary.Status;
import com.intellhub.shared.PublicAddressPolicy;
import com.intellhub.shared.PublicUrlPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/**
 * Real Postgres, real services, a local fake feed server and a fake DNS map: no test touches the real
 * network. The test fetcher allows loopback (where the fake server lives) but nothing else non-public.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@ExtendWith(OutputCaptureExtension.class)
class IngestionServiceTests {

	@TestConfiguration
	static class TestWiring {

		@Bean
		@Primary
		SafeHttpFetcher testFetcher() throws UnknownHostException {
			InetAddress loopback = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
			InetAddress privateAddress = InetAddress.getByAddress(new byte[] { 10, 0, 0, 5 });
			Map<String, InetAddress[]> hosts = Map.of("feed.example", new InetAddress[] { loopback }, "private.example",
					new InetAddress[] { privateAddress });
			DnsResolver dns = new DnsResolver() {
				@Override
				public InetAddress[] resolve(String host) throws UnknownHostException {
					InetAddress[] found = hosts.get(host);
					if (found == null) {
						throw new UnknownHostException("unknown");
					}
					return found;
				}

				@Override
				public String resolveCanonicalHostname(String host) {
					return host;
				}
			};
			FetchSettings settings = new FetchSettings(Duration.ofSeconds(1), Duration.ofMillis(600), Duration.ofSeconds(3),
					1_000_000, 3, "IntellHub/test");
			return new SafeHttpFetcher(settings, PublicUrlPolicy::problem,
					a -> a.isLoopbackAddress() || PublicAddressPolicy.isPublic(a), dns);
		}

		/** Real parser, except that a marker makes it blow up, to prove one bad source cannot stop a run. */
		@Bean
		@Primary
		FeedParser testParser() {
			RomeFeedParser real = new RomeFeedParser();
			return (xml, limits) -> {
				if (new String(xml, StandardCharsets.UTF_8).contains("PARSER-BOOM")) {
					throw new IllegalStateException("boom with SECRET-FEED-TEXT");
				}
				return real.parse(xml, limits);
			};
		}

	}

	private static final String LONG_TEXT = "This article has a reasonably long body with plenty of distinct words in it. ".repeat(6);

	private final IngestionService ingestion;

	private final JdbcTemplate jdbc;

	private FakeFeedServer server;

	IngestionServiceTests(IngestionService ingestion, JdbcTemplate jdbc) {
		this.ingestion = ingestion;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() throws Exception {
		jdbc.update("delete from sources");
		server = new FakeFeedServer();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	// ---- helpers ----

	private UUID source(String type, String name, String path, boolean enabled) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, enabled, created_at) values (?, ?, ?, ?, ?, now())", id, type,
				name, "http://feed.example:" + server.port() + path, enabled);
		return id;
	}

	private UUID rssSource(String name, String path) {
		return source("RSS", name, path, true);
	}

	private static String rfc(Instant instant) {
		return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC));
	}

	private static String item(String title, String link, String description, Instant published) {
		return "<item><title><![CDATA[" + title + "]]></title>" + (link == null ? "" : "<link>" + link + "</link>")
				+ "<description><![CDATA[" + description + "]]></description>"
				+ (published == null ? "" : "<pubDate>" + rfc(published) + "</pubDate>") + "</item>";
	}

	private static String feed(String... items) {
		return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>https://example.com/</link>"
				+ "<description>d</description>" + String.join("", items) + "</channel></rss>";
	}

	private void serve(String path, String xml) {
		server.on(path, FakeFeedServer.body(200, "application/rss+xml", xml));
	}

	private SourceResult resultFor(IngestSummary summary, UUID id) {
		return summary.sources().stream().filter(s -> s.sourceId().equals(id)).findFirst().orElseThrow();
	}

	private Map<String, Object> health(UUID id) {
		return jdbc.queryForMap("select last_status, last_error, consecutive_failures, last_fetched_at from sources where id = ?",
				id);
	}

	private int articleCount() {
		return jdbc.queryForObject("select count(*) from articles", Integer.class);
	}

	// ---- the happy path ----

	@Test
	void storesNewArticlesCleanedAndReportsHealth() {
		Instant published = Instant.now().minus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		serve("/feed", feed(
				item("First post", "https://example.com/first?utm_source=rss&id=7#comments",
						"<p>Hello <b>world</b></p><script>alert(1)</script>", published),
				item("Second post", "https://example.com/second", "Plain text only", published.minusSeconds(60))));
		UUID id = rssSource("Test feed", "/feed");

		IngestSummary summary = ingestion.run();

		assertThat(summary.sourcesTotal()).isEqualTo(1);
		assertThat(summary.sourcesOk()).isEqualTo(1);
		assertThat(summary.articlesCreated()).isEqualTo(2);
		SourceResult result = resultFor(summary, id);
		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.error()).isNull();
		assertThat(result.entries()).isEqualTo(2);

		Map<String, Object> first = jdbc.queryForMap(
				"select title, canonical_url, content_sanitized, excerpt, status, published_at, ingested_at, source_id "
						+ "from articles where title = 'First post'");
		assertThat(first.get("canonical_url")).isEqualTo("https://example.com/first?id=7");
		assertThat((String) first.get("content_sanitized")).isEqualTo("<p>Hello <b>world</b></p>");
		assertThat(first.get("excerpt")).isEqualTo("Hello world");
		assertThat(first.get("status")).isEqualTo("PENDING");
		assertThat(first.get("source_id")).isEqualTo(id);
		assertThat(((java.sql.Timestamp) first.get("published_at")).toInstant()).isEqualTo(published);

		Map<String, Object> health = health(id);
		assertThat(health.get("last_status")).isEqualTo("OK");
		assertThat(health.get("last_error")).isNull();
		assertThat(health.get("consecutive_failures")).isEqualTo(0);
		assertThat(health.get("last_fetched_at")).isNotNull();
	}

	@Test
	void runningAgainStoresNothingNewAndCountsDuplicates() {
		serve("/feed", feed(item("One", "https://example.com/1", "a", null), item("Two", "https://example.com/2", "b", null)));
		rssSource("Test feed", "/feed");

		IngestSummary first = ingestion.run();
		IngestSummary second = ingestion.run();

		assertThat(first.articlesCreated()).isEqualTo(2);
		assertThat(second.articlesCreated()).isZero();
		assertThat(second.duplicates()).isEqualTo(2);
		assertThat(articleCount()).isEqualTo(2);
	}

	@Test
	void theSameArticleUnderTwoUrlsOrTwoSourcesIsStoredOnce() {
		serve("/a", feed(item("Shared story", "https://example.com/story", LONG_TEXT, null),
				item("Shared story", "https://example.com/story-copy", LONG_TEXT, null)));
		serve("/b", feed(item("Shared story", "https://mirror.example/story", LONG_TEXT, null)));
		rssSource("A", "/a");
		rssSource("B", "/b");

		IngestSummary summary = ingestion.run();

		assertThat(summary.articlesCreated()).isEqualTo(1);
		assertThat(summary.duplicates()).isEqualTo(2);
		assertThat(articleCount()).isEqualTo(1);
	}

	// ---- isolation: one bad source never stops the run ----

	@Test
	void everyKindOfBadSourceFailsAloneWithAFixedMessageAndTheGoodOneStillIngests() {
		serve("/ok", feed(item("Good article", "https://example.com/good", "body", null)));
		server.on("/missing", FakeFeedServer.body(404, "text/html", "SECRET-BODY"))
				.on("/html", FakeFeedServer.body(200, "text/html", "<html><body>Not a feed SECRET-BODY</body></html>"))
				.on("/badxml", FakeFeedServer.body(200, "application/xml", "<rss><channel>SECRET-BODY"))
				.on("/boom", FakeFeedServer.body(200, "application/xml", feed(item("PARSER-BOOM", "https://example.com/x", "", null))))
				.on("/slow", FakeFeedServer.sleepThenBody(2500, feed()));
		UUID good = rssSource("Good", "/ok");
		UUID missing = rssSource("Missing", "/missing");
		UUID html = rssSource("Html", "/html");
		UUID badXml = rssSource("Bad xml", "/badxml");
		UUID boom = rssSource("Boom", "/boom");
		UUID slow = rssSource("Slow", "/slow");
		UUID blocked = source("RSS", "Blocked", "/x", true);
		jdbc.update("update sources set url = ? where id = ?", "http://private.example:" + server.port() + "/feed", blocked);

		IngestSummary summary = ingestion.run();

		assertThat(summary.sourcesTotal()).isEqualTo(7);
		assertThat(summary.sourcesOk()).isEqualTo(1);
		assertThat(summary.sourcesFailed()).isEqualTo(6);
		assertThat(resultFor(summary, good).status()).isEqualTo(Status.OK);
		assertThat(resultFor(summary, missing).error()).isEqualTo("HTTP 404");
		assertThat(resultFor(summary, html).error()).isEqualTo("Invalid feed");
		assertThat(resultFor(summary, badXml).error()).isEqualTo("Invalid feed");
		assertThat(resultFor(summary, boom).error()).isEqualTo("Unexpected error");
		assertThat(resultFor(summary, slow).error()).isEqualTo("Timed out");
		assertThat(resultFor(summary, blocked).error()).isEqualTo("Blocked address");
		assertThat(articleCount()).isEqualTo(1);
		for (UUID bad : List.of(missing, html, badXml, boom, slow, blocked)) {
			Map<String, Object> health = health(bad);
			assertThat(health.get("last_status")).isEqualTo("FAILED");
			assertThat(health.get("consecutive_failures")).isEqualTo(1);
			assertThat((String) health.get("last_error")).doesNotContain("SECRET");
		}
		assertThat(health(good).get("last_status")).isEqualTo("OK");
		assertThat(server.requestCount("/feed")).isZero(); // the private address was never contacted
	}

	@Test
	void failuresAccumulateAndRecoveryResetsThem() {
		server.on("/flaky", FakeFeedServer.body(500, "text/plain", "down"));
		UUID id = rssSource("Flaky", "/flaky");

		ingestion.run();
		ingestion.run();
		assertThat(health(id).get("consecutive_failures")).isEqualTo(2);

		// the feed comes back
		serve("/flaky", feed(item("Back", "https://example.com/back", "x", null)));

		ingestion.run();

		Map<String, Object> health = health(id);
		assertThat(health.get("last_status")).isEqualTo("OK");
		assertThat(health.get("consecutive_failures")).isEqualTo(0);
		assertThat(health.get("last_error")).isNull();
	}

	@Test
	void disabledAndUnsupportedSourcesAreNotFetched() {
		serve("/off", feed(item("Off", "https://example.com/off", "x", null)));
		serve("/hn", feed(item("HN", "https://example.com/hn", "x", null)));
		source("RSS", "Disabled", "/off", false);
		UUID hn = source("HN", "Hacker News", "/hn", true);

		IngestSummary summary = ingestion.run();

		assertThat(summary.sourcesTotal()).isEqualTo(1);
		assertThat(summary.sourcesSkipped()).isEqualTo(1);
		assertThat(resultFor(summary, hn).status()).isEqualTo(Status.SKIPPED_UNSUPPORTED);
		assertThat(server.requests()).isEmpty();
		assertThat(health(hn).get("last_status")).isNull();
		assertThat(articleCount()).isZero();
	}

	// ---- source types that are just feeds ----

	private static final String GITHUB_ATOM = """
			<?xml version="1.0" encoding="UTF-8"?>
			<feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
			  <id>tag:github.com,2008:https://github.com/acme/tool/releases</id>
			  <title>Release notes from tool</title>
			  <updated>%1$s</updated>
			  <entry>
			    <id>tag:github.com,2008:Repository/1/v2.0.0</id>
			    <updated>%1$s</updated>
			    <link rel="alternate" type="text/html" href="https://github.com/acme/tool/releases/tag/v2.0.0"/>
			    <title>v2.0.0</title>
			    <content type="html">&lt;p&gt;Faster &lt;b&gt;startup&lt;/b&gt; and a new plugin API.&lt;/p&gt;&lt;script&gt;alert(1)&lt;/script&gt;</content>
			    <author><name>maintainer</name></author>
			  </entry>
			</feed>""";

	@Test
	void gitHubReleasesAndArxivFeedsAreIngestedLikeAnyFeedAndTheirContentIsStillCleaned() {
		String recent = DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(3600));
		server.on("/releases.atom", FakeFeedServer.body(200, "application/atom+xml", GITHUB_ATOM.formatted(recent)));
		serve("/rss/cs.AI", feed(item("A new training method", "https://arxiv.org/abs/2611.00001",
				"arXiv:2611.00001v1 Announce Type: new Abstract: We train models faster.", Instant.now().minusSeconds(3600))));
		UUID github = source("GITHUB_RELEASES", "acme/tool releases", "/releases.atom", true);
		UUID arxiv = source("ARXIV", "arXiv cs.AI", "/rss/cs.AI", true);

		IngestSummary summary = ingestion.run();

		assertThat(resultFor(summary, github).status()).isEqualTo(Status.OK);
		assertThat(resultFor(summary, arxiv).status()).isEqualTo(Status.OK);
		assertThat(summary.articlesCreated()).isEqualTo(2);
		assertThat(health(github).get("last_status")).isEqualTo("OK");
		assertThat(health(arxiv).get("last_status")).isEqualTo("OK");
		String content = jdbc.queryForObject("select content_sanitized from articles where title = 'v2.0.0'", String.class);
		assertThat(content).contains("Faster").doesNotContain("<script").doesNotContain("alert(1)");
		assertThat(jdbc.queryForObject("select excerpt from articles where title = 'A new training method'", String.class))
				.contains("We train models faster");

		assertThat(ingestion.run().duplicates()).isEqualTo(2); // a second run stores nothing new
	}

	@Test
	void aSourcesOwnEntryLimitCapsWhatIsTakenFromABusyFeed() {
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			items.append(item("Paper " + i, "https://arxiv.org/abs/2611.1000" + i, "abstract " + i, Instant.now().minusSeconds(60L * i)));
		}
		serve("/rss/cs.LG", feed(items.toString()));
		UUID arxiv = source("ARXIV", "arXiv cs.LG", "/rss/cs.LG", true);
		jdbc.update("update sources set config = '{\"maxEntries\": 3}'::jsonb where id = ?", arxiv);

		IngestSummary summary = ingestion.run();

		assertThat(resultFor(summary, arxiv).created()).isEqualTo(3);
		assertThat(articleCount()).isEqualTo(3);
	}

	@Test
	void aSourceLimitAboveTheGlobalOneOrOfTheWrongTypeChangesNothing() {
		serve("/rss/a", feed(item("One", "https://example.com/1", "x", null), item("Two", "https://example.com/2", "x", null)));
		UUID a = source("ARXIV", "A", "/rss/a", true);
		jdbc.update("update sources set config = '{\"maxEntries\": 100000}'::jsonb where id = ?", a);
		assertThat(ingestion.run().articlesCreated()).isEqualTo(2);
	}

	@Test
	void aRunWithNoSourcesIsAnEmptySummary() {
		IngestSummary summary = ingestion.run();

		assertThat(summary.sourcesTotal()).isZero();
		assertThat(summary.articlesCreated()).isZero();
		assertThat(summary.sources()).isEmpty();
	}

	// ---- entries ----

	@Test
	void badEntriesAreSkippedWithoutFailingTheSource() {
		serve("/feed", feed(
				item("Good A", "https://example.com/a", "x", null),
				item("Script link", "javascript:alert(1)", "x", null),
				item("   ", "https://example.com/blank-title", "x", null),
				item("Relative", "/relative/path", "x", null),
				item("Long link", "https://example.com/" + "a".repeat(3000), "x", null),
				item("Private link", "http://10.0.0.1/admin", "x", null),
				item("Localhost link", "http://localhost/admin", "x", null),
				item("No link", null, "x", null),
				item("Good B", "https://example.com/b", "x", null)));
		UUID id = rssSource("Messy", "/feed");

		IngestSummary summary = ingestion.run();

		SourceResult result = resultFor(summary, id);
		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.created()).isEqualTo(2);
		assertThat(result.skipped()).isEqualTo(7);
		assertThat(jdbc.queryForList("select title from articles order by title", String.class))
				.containsExactly("Good A", "Good B");
	}

	@Test
	void oldEntriesAreDroppedAndMissingOrFutureDatesBecomeTheFetchTime() {
		Instant now = Instant.now();
		serve("/feed", feed(
				item("Recent", "https://example.com/recent", "x", now.minus(Duration.ofHours(2))),
				item("Ancient", "https://example.com/ancient", "x", now.minus(Duration.ofDays(60))),
				item("Undated", "https://example.com/undated", "x", null),
				item("From the future", "https://example.com/future", "x", now.plus(Duration.ofDays(30)))));
		rssSource("Dates", "/feed");

		IngestSummary summary = ingestion.run();

		assertThat(summary.articlesCreated()).isEqualTo(3);
		assertThat(jdbc.queryForObject("select count(*) from articles where title = 'Ancient'", Integer.class)).isZero();
		for (String title : List.of("Undated", "From the future")) {
			Instant stored = jdbc.queryForObject("select published_at from articles where title = ?", java.sql.Timestamp.class,
					title).toInstant();
			assertThat(Duration.between(stored, now).abs()).isLessThan(Duration.ofMinutes(5));
		}
		Instant recent = jdbc.queryForObject("select published_at from articles where title = 'Recent'",
				java.sql.Timestamp.class).toInstant();
		assertThat(Duration.between(recent, now)).isBetween(Duration.ofHours(1), Duration.ofHours(3));
	}

	@Test
	void aFeedIsLimitedToTheConfiguredNumberOfEntries() {
		String[] items = new String[130];
		for (int i = 0; i < items.length; i++) {
			items[i] = item("Item " + i, "https://example.com/item/" + i, "", null);
		}
		serve("/feed", feed(items));
		rssSource("Big", "/feed");

		IngestSummary summary = ingestion.run();

		assertThat(summary.articlesCreated()).isEqualTo(100);
		assertThat(articleCount()).isEqualTo(100);
	}

	@Test
	void hostileEntryContentIsStoredSanitized() {
		serve("/feed", feed(item("<b>Bold</b> <script>alert(1)</script> title", "https://example.com/evil",
				"<p>ok</p><script>alert(1)</script><img src=x onerror=alert(1)><a href=\"javascript:alert(1)\">x</a>"
						+ "<iframe src=\"https://evil.example\"></iframe><a href=\"https://example.com/fine\">fine</a>",
				null)));
		rssSource("Evil", "/feed");

		ingestion.run();

		Map<String, Object> stored = jdbc.queryForMap("select title, content_sanitized, excerpt from articles");
		// Titles are text, kept as written; the UI must show them as text, never as HTML.
		assertThat(stored.get("title")).isEqualTo("<b>Bold</b> <script>alert(1)</script> title");
		String html = ((String) stored.get("content_sanitized")).toLowerCase();
		assertThat(html).contains("<p>ok</p>").contains("href=\"https://example.com/fine\"").doesNotContain("script")
				.doesNotContain("onerror").doesNotContain("javascript:").doesNotContain("<img").doesNotContain("iframe");
		assertThat((String) stored.get("excerpt")).doesNotContain("<").doesNotContain("alert");
	}

	// ---- one run at a time, and no feed text in logs ----

	@Test
	void aSecondRunWhileOneIsInProgressIsRefused() throws Exception {
		server.on("/slowfeed", FakeFeedServer.sleepThenBody(400, feed(item("S", "https://example.com/s", "x", null))));
		rssSource("Slowish", "/slowfeed");

		CompletableFuture<IngestSummary> firstRun = CompletableFuture.supplyAsync(ingestion::run);
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (server.requestCount("/slowfeed") == 0 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}

		assertThatThrownBy(ingestion::run).isInstanceOf(IngestAlreadyRunningException.class);
		assertThat(firstRun.get().articlesCreated()).isEqualTo(1);
		// and the guard is released afterwards
		assertThat(ingestion.run().sourcesTotal()).isEqualTo(1);
	}

	@Test
	void nothingFromAFeedIsEverLogged(CapturedOutput output) {
		String marker = "LEAKCHECK-7f3a91";
		serve("/" + marker + "-ok", feed(item("Title " + marker, "https://example.com/" + marker, "Body " + marker, null)));
		server.on("/" + marker + "-bad", FakeFeedServer.body(500, "text/plain", "error body " + marker))
				.on("/" + marker + "-boom", FakeFeedServer.body(200, "application/xml",
						feed(item("PARSER-BOOM " + marker, "https://example.com/x", marker, null))));
		rssSource("Source " + marker.length(), "/" + marker + "-ok");
		rssSource("Bad", "/" + marker + "-bad");
		rssSource("Boom", "/" + marker + "-boom");

		ingestion.run();

		assertThat(output.getAll()).contains("Ingestion finished").doesNotContain(marker).doesNotContain("SECRET-FEED-TEXT");
	}

}
