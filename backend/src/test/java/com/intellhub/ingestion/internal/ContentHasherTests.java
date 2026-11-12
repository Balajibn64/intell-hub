package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentHasherTests {

	private static final String LONG_TEXT = "This is a reasonably long article body with plenty of words. ".repeat(10);

	@Test
	void theHashIs64LowercaseHexCharacters() {
		assertThat(ContentHasher.hash("t", LONG_TEXT, "https://a.example/1")).matches("^[0-9a-f]{64}$");
	}

	@Test
	void sameLongContentUnderDifferentUrlsHasTheSameHash() {
		assertThat(ContentHasher.hash("Title", LONG_TEXT, "https://a.example/1"))
				.isEqualTo(ContentHasher.hash("Title", LONG_TEXT, "https://mirror.example/copy"));
	}

	@Test
	void caseAndWhitespaceDoNotChangeTheHash() {
		assertThat(ContentHasher.hash("  The   TITLE ", LONG_TEXT, "https://a.example/1"))
				.isEqualTo(ContentHasher.hash("the title", LONG_TEXT.toUpperCase().replace(" ", "  "), "https://b.example/2"));
	}

	@Test
	void onlyTheStartOfTheTextCounts() {
		String start = "s".repeat(ContentHasher.TEXT_CHARS_HASHED);

		assertThat(ContentHasher.hash("T", start + " ending one", "https://a.example/1"))
				.isEqualTo(ContentHasher.hash("T", start + " a completely different ending", "https://a.example/1"));
	}

	@Test
	void differentTitlesOrContentGiveDifferentHashes() {
		String otherText = "A completely different article body about something else entirely. ".repeat(10);

		assertThat(ContentHasher.hash("One", LONG_TEXT, "https://a.example/1"))
				.isNotEqualTo(ContentHasher.hash("Two", LONG_TEXT, "https://a.example/1"));
		assertThat(ContentHasher.hash("T", LONG_TEXT, "https://a.example/1"))
				.isNotEqualTo(ContentHasher.hash("T", otherText, "https://a.example/1"));
	}

	@Test
	void shortItemsAreNeverMergedAcrossUrls() {
		String a = ContentHasher.hash("Weekly digest", "", "https://a.example/week-1");
		String b = ContentHasher.hash("Weekly digest", "", "https://a.example/week-2");

		assertThat(a).isNotEqualTo(b);
		assertThat(ContentHasher.hash("Weekly digest", "short", "https://a.example/week-1")).isNotEqualTo(a);
	}

	@Test
	void nullsAreTreatedAsEmpty() {
		assertThat(ContentHasher.hash(null, null, "https://a.example/1")).matches("^[0-9a-f]{64}$");
	}

}
