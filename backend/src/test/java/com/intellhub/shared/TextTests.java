package com.intellhub.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextTests {

	@Test
	void shortAndNullValuesAreReturnedAsIs() {
		assertThat(Text.cut("abc", 3)).isEqualTo("abc");
		assertThat(Text.cut("abc", 10)).isEqualTo("abc");
		assertThat(Text.cut("", 0)).isEmpty();
		assertThat(Text.cut(null, 5)).isNull();
	}

	@Test
	void longValuesAreCutToTheLimit() {
		assertThat(Text.cut("abcdef", 4)).isEqualTo("abcd");
		assertThat(Text.cut("abcdef", 0)).isEmpty();
	}

	@Test
	void aSurrogatePairIsNeverSplit() {
		String emoji = "\uD83D\uDE00";

		assertThat(Text.cut("ab" + emoji + "cd", 3)).isEqualTo("ab");
		assertThat(Text.cut("ab" + emoji + "cd", 4)).isEqualTo("ab" + emoji);
		assertThat(Text.cut(emoji, 1)).isEmpty();
	}

}
