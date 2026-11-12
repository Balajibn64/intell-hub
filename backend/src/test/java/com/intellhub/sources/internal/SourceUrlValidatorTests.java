package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.shared.ApiException;
import com.intellhub.sources.SourceType;
import org.junit.jupiter.api.Test;

/** The detailed URL rules are tested in the shared PublicUrlPolicyTests; this covers the wrapper. */
class SourceUrlValidatorTests {

	@Test
	void validateThrowsABadRequestApiException() {
		assertThatThrownBy(() -> SourceUrlValidator.validate("http://127.0.0.1/"))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus().value()).isEqualTo(400);
					assertThat(e.getCode()).isEqualTo("INVALID_SOURCE_URL");
				});
		SourceUrlValidator.validate("https://example.com/feed");
	}

	@Test
	void usesTheSharedRulesAndLength() {
		assertThat(SourceUrlValidator.problem("http://169.254.169.254/")).isNotNull();
		assertThat(SourceUrlValidator.problem("https://example.com/feed")).isNull();
		assertThat(SourceUrlValidator.MAX_LENGTH).isEqualTo(2048);
	}

	// ---- each type may only point at its own place ----

	private static boolean accepts(SourceType type, String url) {
		try {
			SourceUrlValidator.validate(type, url);
			return true;
		}
		catch (ApiException e) {
			assertThat(e.getCode()).isEqualTo("INVALID_SOURCE_URL");
			if (url != null && !url.isEmpty()) {
				assertThat(e.getMessage()).doesNotContain(url); // the address is never echoed
			}
			return false;
		}
	}

	@Test
	void rssStillAcceptsAnyPublicAddress() {
		assertThat(accepts(SourceType.RSS, "https://example.com/feed.xml")).isTrue();
		assertThat(accepts(SourceType.RSS, "http://example.com/feed.xml")).isTrue();
		assertThat(accepts(SourceType.RSS, "http://127.0.0.1/feed")).isFalse();
	}

	@Test
	void hackerNewsOnlyAcceptsTheAlgoliaFrontPage() {
		assertThat(accepts(SourceType.HN, "https://hn.algolia.com/api/v1/search?tags=front_page")).isTrue();
		assertThat(accepts(SourceType.HN, "HTTPS://HN.ALGOLIA.COM/api/v1/search?tags=front_page")).isTrue(); // normalized first
		for (String bad : new String[] { "https://hn.algolia.com/api/v1/search?tags=story", "https://hn.algolia.com/api/v1/search",
				"https://hn.algolia.com/api/v1/search?tags=front_page&query=x", "http://hn.algolia.com/api/v1/search?tags=front_page",
				"https://hn.algolia.com.evil.example/api/v1/search?tags=front_page", "https://evil.example/api/v1/search?tags=front_page",
				"https://hn.algolia.com:8443/api/v1/search?tags=front_page", "https://user@hn.algolia.com/api/v1/search?tags=front_page",
				"https://hn.algolia.com/api/v1/search?tags=front_page#x", "https://news.ycombinator.com/rss",
				"https://hn.algolia.com/api/v1/../v1/search?tags=front_page" }) {
			assertThat(accepts(SourceType.HN, bad)).as(bad).isFalse();
		}
	}

	@Test
	void huggingFaceOnlyAcceptsTheDailyPapersList() {
		assertThat(accepts(SourceType.HUGGINGFACE, "https://huggingface.co/api/daily_papers")).isTrue();
		for (String bad : new String[] { "https://huggingface.co/api/daily_papers?limit=500", "https://huggingface.co/blog/feed.xml",
				"https://huggingface.co.evil.example/api/daily_papers", "http://huggingface.co/api/daily_papers",
				"https://huggingface.co/api/daily_papers/", "https://evil.example/api/daily_papers" }) {
			assertThat(accepts(SourceType.HUGGINGFACE, bad)).as(bad).isFalse();
		}
	}

	@Test
	void gitHubReleasesOnlyAcceptARepositoryReleasesFeed() {
		assertThat(accepts(SourceType.GITHUB_RELEASES, "https://github.com/spring-projects/spring-boot/releases.atom")).isTrue();
		assertThat(accepts(SourceType.GITHUB_RELEASES, "https://GitHub.com/ollama/ollama/releases.atom")).isTrue();
		assertThat(accepts(SourceType.GITHUB_RELEASES, "https://github.com/a.b/c_d-e/releases.atom")).isTrue();
		for (String bad : new String[] { "https://github.com/spring-projects/spring-boot/tags.atom", "https://github.com/spring-projects/releases.atom",
				"https://github.com/spring-projects/spring-boot/releases", "https://github.com/spring-projects/spring-boot/releases.atom?x=1",
				"https://github.com/../x/releases.atom", "https://github.com/x/../releases.atom", "https://github.com/./x/releases.atom",
				"https://github.com/a/b/c/releases.atom", "https://github.com@evil.example/a/b/releases.atom",
				"https://github.com.evil.example/a/b/releases.atom", "https://raw.githubusercontent.com/a/b/releases.atom",
				"http://github.com/a/b/releases.atom", "https://github.com:444/a/b/releases.atom", "https://github.com/a b/c/releases.atom",
				"https://github.com/" + "a".repeat(40) + "/b/releases.atom", "https://github.com/a/" + "b".repeat(101) + "/releases.atom" }) {
			assertThat(accepts(SourceType.GITHUB_RELEASES, bad)).as(bad).isFalse();
		}
	}

	@Test
	void arxivOnlyAcceptsACategoryFeedOnItsFeedHost() {
		assertThat(accepts(SourceType.ARXIV, "https://rss.arxiv.org/rss/cs.AI")).isTrue();
		assertThat(accepts(SourceType.ARXIV, "https://rss.arxiv.org/atom/cs.SE")).isTrue();
		assertThat(accepts(SourceType.ARXIV, "https://rss.arxiv.org/rss/stat.ML")).isTrue();
		assertThat(accepts(SourceType.ARXIV, "https://rss.arxiv.org/rss/cs")).isTrue();
		for (String bad : new String[] { "https://export.arxiv.org/api/query?search_query=cat:cs.AI", "https://arxiv.org/rss/cs.AI",
				"https://rss.arxiv.org/json/cs.AI", "https://rss.arxiv.org/rss/cs.AI/extra", "https://rss.arxiv.org/rss/cs.AI?x=1",
				"https://rss.arxiv.org/rss/../cs.AI", "https://rss.arxiv.org.evil.example/rss/cs.AI", "http://rss.arxiv.org/rss/cs.AI",
				"https://rss.arxiv.org/rss/", "https://rss.arxiv.org/rss/cs.AI.AI.AI" }) {
			assertThat(accepts(SourceType.ARXIV, bad)).as(bad).isFalse();
		}
	}

	@Test
	void thePublicRulesStillApplyFirstForEveryType() {
		for (SourceType type : SourceType.values()) {
			assertThat(accepts(type, "http://169.254.169.254/")).isFalse();
			assertThat(accepts(type, "")).isFalse();
			assertThat(accepts(type, null)).isFalse();
		}
	}

}
