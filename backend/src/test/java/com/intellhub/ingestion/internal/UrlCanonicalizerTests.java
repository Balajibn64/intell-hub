package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.articles.NewArticle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlCanonicalizerTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			https://example.com/post|https://example.com/post
			HTTPS://EXAMPLE.COM/Post|https://example.com/Post
			https://example.com:443/post|https://example.com/post
			http://example.com:80/post|http://example.com/post
			https://example.com:8443/post|https://example.com:8443/post
			https://example.com./post|https://example.com/post
			https://example.com/post#section-2|https://example.com/post
			https://example.com/post?utm_source=x&utm_medium=y|https://example.com/post
			https://example.com/post?UTM_Campaign=x|https://example.com/post
			https://example.com/post?fbclid=abc|https://example.com/post
			https://example.com/post?gclid=1&id=7|https://example.com/post?id=7
			https://example.com/post?id=7&utm_term=z&page=2|https://example.com/post?id=7&page=2
			https://example.com/post?b=2&a=1|https://example.com/post?b=2&a=1
			https://example.com/post?utm_source=x#top|https://example.com/post
			https://example.com/post?mc_cid=1&mc_eid=2|https://example.com/post
			https://example.com/|https://example.com/
			https://example.com|https://example.com
			""")
	void equivalentLinksCollapseToOneForm(String link, String expected) {
		assertThat(UrlCanonicalizer.canonicalize(link)).isEqualTo(expected);
	}

	@Test
	void differentPathsAndRealParametersStayDifferent() {
		assertThat(UrlCanonicalizer.canonicalize("https://example.com/a")).isNotEqualTo(
				UrlCanonicalizer.canonicalize("https://example.com/b"));
		assertThat(UrlCanonicalizer.canonicalize("https://example.com/a?id=1")).isNotEqualTo(
				UrlCanonicalizer.canonicalize("https://example.com/a?id=2"));
		assertThat(UrlCanonicalizer.canonicalize("https://example.com/A")).isNotEqualTo(
				UrlCanonicalizer.canonicalize("https://example.com/a"));
		assertThat(UrlCanonicalizer.canonicalize("https://example.com/a")).isNotEqualTo(
				UrlCanonicalizer.canonicalize("http://example.com/a"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "javascript:alert(1)", "ftp://example.com/x", "file:///etc/passwd", "/relative/path",
			"https://127.0.0.1/x", "http://localhost/x", "https://user:pw@example.com/x", "http://10.0.0.1/x",
			"http://[::1]/x", "https://example.com/has space", "" })
	void unacceptableLinksGiveNull(String link) {
		assertThat(UrlCanonicalizer.canonicalize(link)).isNull();
	}

	@Test
	void nullAndOverlongLinksGiveNull() {
		assertThat(UrlCanonicalizer.canonicalize(null)).isNull();
		assertThat(UrlCanonicalizer.canonicalize("https://example.com/" + "a".repeat(NewArticle.MAX_URL))).isNull();
	}

}
