package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/**
 * V9 switches off special-type sources that were created before the per-type address rules existed. The migration
 * already ran at startup on an empty table, so its SQL is run again here over rows made to look like legacy ones.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SourceTypeAddressMigrationTests {

	private final JdbcTemplate jdbc;

	SourceTypeAddressMigrationTests(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@BeforeEach
	void clean() {
		jdbc.update("delete from sources");
	}

	private void source(String type, String url) {
		jdbc.update("insert into sources (id, type, name, url, enabled, created_at) values (?, ?, 'S', ?, true, now())", UUID.randomUUID(), type, url);
	}

	private boolean enabled(String type, String url) {
		return jdbc.queryForObject("select enabled from sources where type = ? and url = ?", Boolean.class, type, url);
	}

	@Test
	void legacySourcesAtTheWrongAddressAreSwitchedOffAndEverythingElseIsLeftAlone() throws Exception {
		String[][] valid = { { "RSS", "https://anything.example/feed" }, { "RSS", "http://also.example/rss" },
				{ "HN", "https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=30" }, { "HUGGINGFACE", "https://huggingface.co/api/daily_papers" },
				{ "GITHUB_RELEASES", "https://github.com/ollama/ollama/releases.atom" }, { "ARXIV", "https://rss.arxiv.org/rss/cs.AI" },
				{ "ARXIV", "https://rss.arxiv.org/atom/astro-ph" }, { "ARXIV", "https://rss.arxiv.org/rss/cond-mat.mes-hall" } };
		String[][] legacy = { { "HN", "https://attacker.example/x" }, { "HN", "https://hn.algolia.com/api/v1/search?tags=story" },
				{ "HUGGINGFACE", "https://huggingface.co/api/daily_papers?limit=9999" }, { "HUGGINGFACE", "https://evil.example/papers" },
				{ "GITHUB_RELEASES", "https://github.com/a/b/tags.atom" }, { "GITHUB_RELEASES", "https://github.com/../x/releases.atom" },
				{ "GITHUB_RELEASES", "https://example.com/a/b/releases.atom" }, { "ARXIV", "https://export.arxiv.org/api/query?x=1" },
				{ "ARXIV", "https://rss.arxiv.org/rss/cs.AI/extra" }, { "ARXIV", "http://rss.arxiv.org/rss/cs.AI" } };
		for (String[] s : valid) {
			source(s[0], s[1]);
		}
		for (String[] s : legacy) {
			source(s[0], s[1]);
		}

		String sql = new String(new ClassPathResource("db/migration/V9__source_type_addresses.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		jdbc.execute(sql);

		for (String[] s : valid) {
			assertThat(enabled(s[0], s[1])).as(s[1]).isTrue();
		}
		for (String[] s : legacy) {
			assertThat(enabled(s[0], s[1])).as(s[1]).isFalse();
		}
		assertThat(jdbc.queryForObject("select count(*) from sources", Integer.class)).isEqualTo(valid.length + legacy.length); // switched off, not deleted
	}

}
