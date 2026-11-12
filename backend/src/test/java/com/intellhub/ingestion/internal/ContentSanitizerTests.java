package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.articles.NewArticle;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentSanitizerTests {

	private static final String BASE = "https://example.com/posts/1";

	private final ContentSanitizer sanitizer = new ContentSanitizer();

	private String clean(String html) {
		return sanitizer.sanitizeHtml(html, BASE);
	}

	// ---- what is kept ----

	@Test
	void keepsTheFormattingAllowlist() {
		String html = "<h2>Title</h2><p>Some <strong>bold</strong>, <em>em</em>, <b>b</b>, <i>i</i> and <code>code</code>.</p>"
				+ "<pre>pre</pre><blockquote>quote</blockquote><ul><li>one</li></ul><ol><li>two</li></ol><hr><p>a<br>b</p>";

		assertThat(clean(html)).isEqualTo(html.replace("<hr>", "<hr>").replace("<br>", "<br>"));
	}

	@Test
	void linksKeepOnlyAnHttpsOrHttpHrefAndAlwaysGetASafeRel() {
		String out = clean("<a href=\"https://other.example/a?b=1\" target=\"_blank\" onclick=\"x()\" class=\"c\" "
				+ "style=\"color:red\" id=\"i\">link</a>");

		assertThat(out).isEqualTo("<a href=\"https://other.example/a?b=1\" rel=\"nofollow noopener noreferrer\">link</a>");
	}

	@Test
	void relativeLinksBecomeAbsoluteAgainstTheEntryUrl() {
		assertThat(clean("<a href=\"/about\">x</a>")).contains("href=\"https://example.com/about\"");
		assertThat(clean("<a href=\"next\">x</a>")).contains("href=\"https://example.com/posts/next\"");
	}

	@Test
	void relativeLinksAreDroppedWhenThereIsNoBase() {
		assertThat(sanitizer.sanitizeHtml("<a href=\"/about\">x</a>", "")).doesNotContain("href");
	}

	// ---- what is removed ----

	@ParameterizedTest
	@ValueSource(strings = {
			"<script>alert(1)</script>",
			"<SCRIPT SRC=https://evil.example/x.js></SCRIPT>",
			"<img src=x onerror=alert(1)>",
			"<img src=\"https://tracker.example/pixel.gif\">",
			"<svg onload=alert(1)><circle/></svg>",
			"<iframe src=\"https://evil.example\"></iframe>",
			"<object data=\"x.swf\"></object><embed src=\"x.swf\">",
			"<form action=\"https://evil.example\"><input name=p></form>",
			"<style>body{display:none}</style>",
			"<link rel=stylesheet href=\"https://evil.example/x.css\">",
			"<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\">",
			"<base href=\"https://evil.example/\">",
			"<video src=x onerror=alert(1)></video><audio src=x onerror=alert(1)></audio>",
			"<p onmouseover=\"alert(1)\" style=\"x\" class=\"y\" id=\"z\">text</p>",
			"<a href=\"javascript:alert(1)\">x</a>",
			"<a href=\"JaVaScRiPt:alert(1)\">x</a>",
			"<a href=\"  javascript:alert(1)\">x</a>",
			"<a href=\"jav&#x09;ascript:alert(1)\">x</a>",
			"<a href=\"java\nscript:alert(1)\">x</a>",
			"<a href=\"&#106;avascript:alert(1)\">x</a>",
			"<a href=\"vbscript:msgbox(1)\">x</a>",
			"<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">x</a>",
			"<a href=\"file:///etc/passwd\">x</a>",
			"<a href=\"ftp://example.com/x\">x</a>",
			"<a href=\"mailto:a@b.c\">x</a>",
			"<<script>script>alert(1)<</script>/script>",
			"<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\">",
			"<svg><style><img src onerror=alert(1)></style></svg>",
			"<math><mtext><table><mglyph><style><!--</style><img title=\"--&gt;&lt;img src onerror=alert(1)&gt;\">",
			"<div style=\"background:url(javascript:alert(1))\">x</div>",
			"<a href=\"https://example.com\" target=\"_blank\">x</a>" })
	void dangerousMarkupIsRemoved(String html) {
		String out = clean(html).toLowerCase();

		assertThat(out).doesNotContain("<script").doesNotContain("<img").doesNotContain("<svg").doesNotContain("<iframe")
				.doesNotContain("<style").doesNotContain("<form").doesNotContain("<input").doesNotContain("<object")
				.doesNotContain("<embed").doesNotContain("<link").doesNotContain("<meta").doesNotContain("<base")
				.doesNotContain("<video").doesNotContain("<audio").doesNotContain("onerror").doesNotContain("onload")
				.doesNotContain("onclick").doesNotContain("onmouseover").doesNotContain("javascript:")
				.doesNotContain("vbscript:").doesNotContain("data:").doesNotContain("file:").doesNotContain("mailto:")
				.doesNotContain("ftp:").doesNotContain("style=").doesNotContain("class=").doesNotContain("target=")
				.doesNotContain("alert(1)");
	}

	@Test
	void everyLinkInTheOutputIsHttpOrHttpsWithTheSafeRel() {
		String out = clean("<a href=\"https://a.example/\">1</a><a href=\"http://b.example/\">2</a>"
				+ "<a href=\"javascript:x\">3</a><a name=\"anchor\">4</a><a>5</a>");

		org.jsoup.nodes.Document parsed = org.jsoup.Jsoup.parseBodyFragment(out);
		parsed.select("a[href]").forEach(a -> {
			assertThat(a.attr("href")).matches("https?://.*");
			assertThat(a.attr("rel")).isEqualTo("nofollow noopener noreferrer");
		});
		assertThat(out).contains("3").contains("4").contains("5");
	}

	// ---- size and robustness ----

	@Test
	void nullAndEmptyInputGiveAnEmptyResult() {
		assertThat(sanitizer.sanitizeHtml(null, BASE)).isEmpty();
		assertThat(sanitizer.sanitizeHtml("   ", BASE)).isEmpty();
		assertThat(sanitizer.toPlainText(null)).isEmpty();
	}

	@Test
	void veryLargeContentIsReducedToAShortSafeParagraph() {
		String huge = "<p>" + "word &amp; <b>more</b> ".repeat(20_000) + "</p><script>alert(1)</script>";

		String out = clean(huge);

		assertThat(out.length()).isLessThanOrEqualTo(NewArticle.MAX_CONTENT);
		assertThat(out).startsWith("<p>").endsWith("</p>").doesNotContain("<script").doesNotContain("<b>");
	}

	@Test
	void absurdlyDeepNestingDoesNotCrashOrHang() {
		String deep = "<div>".repeat(60_000) + "bottom" + "</div>".repeat(60_000);

		long start = System.nanoTime();
		String out = clean(deep);
		String text = sanitizer.toPlainText(deep);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
		assertThat(out).doesNotContain("<div");
		assertThat(text).contains("bottom");
	}

	@Test
	void brokenHtmlComesOutWellFormed() {
		String out = clean("<p>unclosed <b>bold <i>italic<p>next</blockquote></ul>");

		org.jsoup.nodes.Document reparsed = org.jsoup.Jsoup.parseBodyFragment(out);
		assertThat(reparsed.body().text()).contains("unclosed").contains("next");
	}

	// ---- plain text, titles, excerpts ----

	@Test
	void plainTextDropsScriptsSeparatesBlocksAndDecodesEntities() {
		String text = sanitizer.toPlainText("<p>One &amp; two</p><script>alert(1)</script><p>three</p><style>x{}</style>"
				+ "<ul><li>a</li><li>b</li></ul>Caf&eacute;<br>end");

		assertThat(text).isEqualTo("One & two three a b Café end");
	}

	@Test
	void titlesAreTextNotHtmlSoDeveloperTitlesKeepTheirWords() {
		assertThat(sanitizer.title("Using <form> validation in React")).isEqualTo("Using <form> validation in React");
		assertThat(sanitizer.title("Why <script> tags block rendering")).isEqualTo("Why <script> tags block rendering");
		assertThat(sanitizer.title("List<String> in Java 21")).isEqualTo("List<String> in Java 21");
		assertThat(sanitizer.title("<b>Bold</b> stays as written")).isEqualTo("<b>Bold</b> stays as written");
	}

	@Test
	void titleEntitiesAreDecodedAndWhitespaceTidied() {
		assertThat(sanitizer.title("Tom &amp; Jerry&#8217;s  \n\t new   show")).isEqualTo("Tom & Jerry\u2019s new show");
		assertThat(sanitizer.title("   padded   ")).isEqualTo("padded");
		assertThat(sanitizer.title(null)).isEmpty();
		assertThat(sanitizer.title("\u0000\u0007\u001b[31mcontrol\u007f")).isEqualTo("[31mcontrol");
	}

	@Test
	void invisibleAndDirectionOverrideCharactersAreRemovedFromTitles() {
		// A right-to-left override can make "txt.exe" display as "exe.txt"; zero-width characters hide text.
		assertThat(sanitizer.title("invoice\u202Etxt.exe")).isEqualTo("invoicetxt.exe");
		assertThat(sanitizer.title("pa\u200Bss\u200Cword\u2060\uFEFF")).isEqualTo("password");
		assertThat(sanitizer.title("a\u2066b\u2069c")).isEqualTo("abc");
	}

	@Test
	void titlesAndAuthorsAreBoundedAndAuthorsMayBeNull() {
		assertThat(sanitizer.title("t".repeat(500))).hasSize(NewArticle.MAX_TITLE);
		assertThat(sanitizer.author("  Ada Lovelace ")).isEqualTo("Ada Lovelace");
		assertThat(sanitizer.author("a".repeat(500))).hasSize(NewArticle.MAX_AUTHOR);
		assertThat(sanitizer.author("   ")).isNull();
		assertThat(sanitizer.author(null)).isNull();
	}

	@Test
	void excerptsAreBoundedAndEndInAnEllipsisWhenCut() {
		assertThat(sanitizer.excerpt("short")).isEqualTo("short");
		String cut = sanitizer.excerpt("word ".repeat(500));
		assertThat(cut.length()).isLessThanOrEqualTo(NewArticle.MAX_EXCERPT);
		assertThat(cut).endsWith("…");
		assertThat(sanitizer.excerpt(null)).isEmpty();
	}

	@Test
	void cuttingNeverSplitsASurrogatePair() {
		String emoji = "😀";
		String text = "x".repeat(NewArticle.MAX_TITLE - 1) + emoji + "tail";

		String title = sanitizer.title(text);

		assertThat(title).hasSize(NewArticle.MAX_TITLE - 1);
		assertThat(Character.isHighSurrogate(title.charAt(title.length() - 1))).isFalse();
		String excerpt = sanitizer.excerpt("y".repeat(NewArticle.MAX_EXCERPT - 2) + emoji + "tail");
		assertThat(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 2))).isFalse();
	}

}
