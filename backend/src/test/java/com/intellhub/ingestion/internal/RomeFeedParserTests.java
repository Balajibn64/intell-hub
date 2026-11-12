package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RomeFeedParserTests {

	private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

	private static final ParseLimits LIMITS = new ParseLimits(100, NOW.minus(Duration.ofDays(14)));

	private final RomeFeedParser parser = new RomeFeedParser();

	private ParsedFeed parse(String xml) throws FeedParseException {
		return parser.parse(xml.getBytes(StandardCharsets.UTF_8), LIMITS);
	}

	private static String rss(String items) {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\" "
				+ "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><channel><title>Feed</title><link>https://example.com/</link>"
				+ "<description>d</description>" + items + "</channel></rss>";
	}

	// ---- formats ----

	@Test
	void parsesRss2() throws Exception {
		ParsedFeed feed = parse(rss("""
				<item><title>First &amp; best</title><link>https://example.com/1</link>
				<description>Short summary</description>
				<content:encoded><![CDATA[<p>Full <b>body</b></p>]]></content:encoded>
				<dc:creator>Ada Lovelace</dc:creator>
				<pubDate>Tue, 10 Nov 2026 10:00:00 GMT</pubDate></item>
				<item><title>Second</title><link>https://example.com/2</link><description>Only description</description></item>
				"""));

		assertThat(feed.entries()).hasSize(2);
		ParsedEntry first = feed.entries().get(0);
		assertThat(first.title()).isEqualTo("First & best");
		assertThat(first.link()).isEqualTo("https://example.com/1");
		assertThat(first.author()).isEqualTo("Ada Lovelace");
		assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-11-10T10:00:00Z"));
		assertThat(first.contentHtml()).isEqualTo("<p>Full <b>body</b></p>");
		ParsedEntry second = feed.entries().get(1);
		assertThat(second.contentHtml()).isEqualTo("Only description");
		assertThat(second.publishedAt()).isNull();
		assertThat(second.author()).isNull();
	}

	@Test
	void parsesAtom() throws Exception {
		ParsedFeed feed = parse("""
				<?xml version="1.0" encoding="utf-8"?>
				<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom feed</title><id>urn:feed</id><updated>2026-11-11T00:00:00Z</updated>
				<entry><title>Atom entry</title><id>urn:entry:1</id>
				<link rel="alternate" href="https://example.com/atom/1"/>
				<updated>2026-11-11T08:30:00Z</updated>
				<author><name>Grace Hopper</name></author>
				<content type="html">&lt;p&gt;Hello&lt;/p&gt;</content></entry></feed>
				""");

		assertThat(feed.entries()).hasSize(1);
		ParsedEntry entry = feed.entries().get(0);
		assertThat(entry.link()).isEqualTo("https://example.com/atom/1");
		assertThat(entry.author()).isEqualTo("Grace Hopper");
		assertThat(entry.publishedAt()).isEqualTo(Instant.parse("2026-11-11T08:30:00Z"));
		assertThat(entry.contentHtml()).isEqualTo("<p>Hello</p>");
	}

	@Test
	void parsesRss1Rdf() throws Exception {
		ParsedFeed feed = parse("""
				<?xml version="1.0"?>
				<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
				<channel rdf:about="https://example.com/"><title>RDF</title><link>https://example.com/</link><description>d</description></channel>
				<item rdf:about="https://example.com/rdf/1"><title>RDF item</title><link>https://example.com/rdf/1</link>
				<description>RDF body</description><dc:date>2026-11-09T12:00:00Z</dc:date></item></rdf:RDF>
				""");

		assertThat(feed.entries()).hasSize(1);
		assertThat(feed.entries().get(0).title()).isEqualTo("RDF item");
		assertThat(feed.entries().get(0).publishedAt()).isEqualTo(Instant.parse("2026-11-09T12:00:00Z"));
	}

	@Test
	void honoursTheDeclaredEncoding() throws Exception {
		String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + rss("<item><title>Café über</title>"
				+ "<link>https://example.com/enc</link></item>").replaceFirst("^<\\?xml[^>]*\\?>", "");

		ParsedFeed feed = parser.parse(xml.getBytes(StandardCharsets.ISO_8859_1), LIMITS);

		assertThat(feed.entries().get(0).title()).isEqualTo("Café über");
	}

	// ---- skipping and limits ----

	@Test
	void skipsEntriesWithoutALinkOrATitleAndCountsThem() throws Exception {
		ParsedFeed feed = parse(rss("""
				<item><title>No link</title></item>
				<item><link>https://example.com/no-title</link></item>
				<item><title>   </title><link>https://example.com/blank-title</link></item>
				<item><title>Relative link</title><link>/relative/path</link></item>
				<item><title>Script link</title><link>javascript:alert(1)</link></item>
				<item><title>Good</title><link>https://example.com/good</link></item>
				"""));

		assertThat(feed.entries()).extracting(ParsedEntry::title).containsExactly("Good");
		assertThat(feed.skippedInvalid()).isEqualTo(5);
	}

	@Test
	void anOverlongLinkIsSkipped() throws Exception {
		ParsedFeed feed = parse(rss("<item><title>Long</title><link>https://example.com/" + "a".repeat(2100)
				+ "</link></item>"));

		assertThat(feed.entries()).isEmpty();
		assertThat(feed.skippedInvalid()).isEqualTo(1);
	}

	@Test
	void usesAnHttpGuidWhenThereIsNoLink() throws Exception {
		ParsedFeed feed = parse(rss("<item><title>Guid only</title><guid>https://example.com/guid/1</guid></item>"));

		assertThat(feed.entries()).extracting(ParsedEntry::link).containsExactly("https://example.com/guid/1");
	}

	@Test
	void takesAtMostTheConfiguredNumberOfEntries() throws Exception {
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < 150; i++) {
			items.append("<item><title>T").append(i).append("</title><link>https://example.com/").append(i)
					.append("</link></item>");
		}

		ParsedFeed feed = parse(rss(items.toString()));

		assertThat(feed.entries()).hasSize(100);
		assertThat(feed.entries().get(0).title()).isEqualTo("T0");
	}

	@Test
	void skipsOldEntriesButKeepsUndatedOnes() throws Exception {
		ParsedFeed feed = parse(rss("""
				<item><title>Recent</title><link>https://example.com/recent</link><pubDate>Tue, 10 Nov 2026 10:00:00 GMT</pubDate></item>
				<item><title>Old</title><link>https://example.com/old</link><pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate></item>
				<item><title>Undated</title><link>https://example.com/undated</link></item>
				"""));

		assertThat(feed.entries()).extracting(ParsedEntry::title).containsExactly("Recent", "Undated");
		assertThat(feed.skippedOld()).isEqualTo(1);
	}

	@Test
	void hugeFieldsAreBoundedWithoutSplittingACharacter() throws Exception {
		String emoji = "😀";
		ParsedFeed feed = parse(rss("<item><title>" + "t".repeat(RomeFeedParser.MAX_TITLE_CHARS - 1) + emoji + "tail</title>"
				+ "<link>https://example.com/big</link><description>" + "d".repeat(RomeFeedParser.MAX_CONTENT_CHARS + 500)
				+ "</description></item>"));

		ParsedEntry entry = feed.entries().get(0);
		assertThat(entry.title()).hasSize(RomeFeedParser.MAX_TITLE_CHARS - 1);
		assertThat(entry.contentHtml()).hasSize(RomeFeedParser.MAX_CONTENT_CHARS);
	}

	@Test
	void markupIsPassedThroughUntouchedBecauseTheSanitizerOwnsThat() throws Exception {
		ParsedFeed feed = parse(rss("<item><title><![CDATA[<b>Bold</b> <script>alert(1)</script>]]></title>"
				+ "<link>https://example.com/x</link></item>"));

		assertThat(feed.entries().get(0).title()).contains("<script>");
	}

	// ---- not a feed ----

	@Test
	void rejectsEmptyAndNonFeedInput() {
		for (String bad : new String[] { "", "   ", "not xml at all", "<html><body>Hello</body></html>", "<rss><channel>",
				"{\"json\": true}", "<?xml version=\"1.0\"?><note>hi</note>" }) {
			assertThatThrownBy(() -> parse(bad)).as(bad).isInstanceOf(FeedParseException.class)
					.hasMessage("Not a valid feed");
		}
		assertThatThrownBy(() -> parser.parse(null, LIMITS)).isInstanceOf(FeedParseException.class);
		assertThatThrownBy(() -> parser.parse(new byte[] { 0, 1, 2, (byte) 0xff }, LIMITS))
				.isInstanceOf(FeedParseException.class);
	}

	// ---- hostile XML ----

	@Test
	void anExternalFileEntityIsRefusedAndNothingIsRead() {
		String xxe = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
				+ "<rss version=\"2.0\"><channel><title>t</title><link>https://example.com/</link><description>d</description>"
				+ "<item><title>&xxe;</title><link>https://example.com/x</link></item></channel></rss>";

		assertThatThrownBy(() -> parse(xxe)).isInstanceOf(FeedParseException.class).hasMessage("Not a valid feed");
	}

	@Test
	void anExternalHttpEntityIsRefusedAndNoRequestIsMade() throws Exception {
		try (FakeFeedServer server = new FakeFeedServer()) {
			server.on("/evil.dtd", FakeFeedServer.body(200, "text/plain", "<!ENTITY leak 'x'>"))
					.on("/entity", FakeFeedServer.body(200, "text/plain", "stolen"));
			String xxe = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY ext SYSTEM \"http://127.0.0.1:" + server.port()
					+ "/entity\"><!ENTITY % remote SYSTEM \"http://127.0.0.1:" + server.port() + "/evil.dtd\">%remote;]>"
					+ "<rss version=\"2.0\"><channel><title>&ext;</title><link>https://example.com/</link><description>d</description>"
					+ "<item><title>&ext;</title><link>https://example.com/x</link></item></channel></rss>";

			assertThatThrownBy(() -> parse(xxe)).isInstanceOf(FeedParseException.class);
			assertThat(server.requests()).isEmpty();
		}
	}

	@Test
	void anExternalDtdIsRefusedAndNoRequestIsMade() throws Exception {
		try (FakeFeedServer server = new FakeFeedServer()) {
			server.on("/feed.dtd", FakeFeedServer.body(200, "text/plain", "<!ELEMENT rss ANY>"));
			String withDtd = "<?xml version=\"1.0\"?><!DOCTYPE rss SYSTEM \"http://127.0.0.1:" + server.port() + "/feed.dtd\">"
					+ "<rss version=\"2.0\"><channel><title>t</title><link>https://example.com/</link><description>d</description>"
					+ "</channel></rss>";

			assertThatThrownBy(() -> parse(withDtd)).isInstanceOf(FeedParseException.class);
			assertThat(server.requests()).isEmpty();
		}
	}

	@Test
	void aBillionLaughsPayloadIsRefusedQuickly() {
		String bomb = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">"
				+ "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
				+ "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">"
				+ "<!ENTITY lol4 \"&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;\">"
				+ "<!ENTITY lol5 \"&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;\">]>"
				+ "<rss version=\"2.0\"><channel><title>&lol5;</title><link>https://example.com/</link><description>d</description>"
				+ "</channel></rss>";

		long start = System.nanoTime();

		assertThatThrownBy(() -> parse(bomb)).isInstanceOf(FeedParseException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void evenABenignDoctypeIsRefused() {
		String benign = "<?xml version=\"1.0\"?><!DOCTYPE rss><rss version=\"2.0\"><channel><title>t</title>"
				+ "<link>https://example.com/</link><description>d</description></channel></rss>";

		assertThatThrownBy(() -> parse(benign)).isInstanceOf(FeedParseException.class);
	}

	@Test
	void theErrorNeverRepeatsTheDocument() {
		String secretish = "<rss><channel><title>TOP-SECRET-MARKER";

		assertThatThrownBy(() -> parse(secretish)).isInstanceOf(FeedParseException.class)
				.satisfies(e -> assertThat(String.valueOf(e.getMessage()) + e.getCause()).doesNotContain("TOP-SECRET-MARKER"));
	}

}
