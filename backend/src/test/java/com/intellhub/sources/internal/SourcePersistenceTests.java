package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.sources.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SourcePersistenceTests {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private final SourceRepository sources;

	private final JdbcTemplate jdbc;

	SourcePersistenceTests(SourceRepository sources, JdbcTemplate jdbc) {
		this.sources = sources;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void clean() {
		sources.deleteAll();
	}

	private static Source source(SourceType type, String url) {
		return new Source(UUID.randomUUID(), type, "Example", url, Map.of(), NOW);
	}

	private void insertRaw(String type, String lastStatus, int failures, String configJson) {
		jdbc.update("""
				insert into sources (id, type, name, url, config, enabled, last_status, consecutive_failures, created_at)
				values (?, ?, 'n', ?, ?::jsonb, true, ?, ?, now())
				""", UUID.randomUUID(), type, "https://example.com/" + UUID.randomUUID(), configJson, lastStatus,
				failures);
	}

	@Test
	void newSourceHasHealthDefaults() {
		Source saved = sources.saveAndFlush(source(SourceType.RSS, "https://example.com/feed"));

		Source loaded = sources.findById(saved.getId()).orElseThrow();

		assertThat(loaded.isEnabled()).isTrue();
		assertThat(loaded.getLastFetchedAt()).isNull();
		assertThat(loaded.getLastStatus()).isNull();
		assertThat(loaded.getLastError()).isNull();
		assertThat(loaded.getConsecutiveFailures()).isZero();
		assertThat(loaded.getConfig()).isEmpty();
	}

	@Test
	void configRoundTripsAsJson() {
		Map<String, Object> config = Map.of("feed", "top", "minScore", 100, "tags", List.of("ai", "ml"),
				"nested", Map.of("flag", true));
		Source saved = sources.saveAndFlush(
				new Source(UUID.randomUUID(), SourceType.HN, "HN", "https://news.ycombinator.com/", config, NOW));

		Source loaded = sources.findById(saved.getId()).orElseThrow();

		assertThat(loaded.getConfig()).containsEntry("feed", "top").containsEntry("minScore", 100)
				.containsEntry("tags", List.of("ai", "ml")).containsEntry("nested", Map.of("flag", true));
		assertThat(jdbc.queryForObject("select jsonb_typeof(config) from sources where id = ?", String.class,
				saved.getId())).isEqualTo("object");
	}

	@Test
	void sameTypeAndUrlIsRejectedButTheSameUrlWithAnotherTypeIsAllowed() {
		sources.saveAndFlush(source(SourceType.RSS, "https://example.com/feed"));
		Source duplicate = source(SourceType.RSS, "https://example.com/feed");

		assertThatThrownBy(() -> sources.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(sources.saveAndFlush(source(SourceType.ARXIV, "https://example.com/feed"))).isNotNull();
	}

	@Test
	void existsByTypeAndUrlMatchesBothFields() {
		sources.saveAndFlush(source(SourceType.RSS, "https://example.com/feed"));

		assertThat(sources.existsByTypeAndUrl(SourceType.RSS, "https://example.com/feed")).isTrue();
		assertThat(sources.existsByTypeAndUrl(SourceType.HN, "https://example.com/feed")).isFalse();
		assertThat(sources.existsByTypeAndUrl(SourceType.RSS, "https://example.com/other")).isFalse();
	}

	@Test
	void databaseRejectsAnUnknownType() {
		assertThatThrownBy(() -> insertRaw("PODCAST", null, 0, "{}")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsAnUnknownLastStatus() {
		assertThatThrownBy(() -> insertRaw("RSS", "MAYBE", 0, "{}")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsNegativeFailureCounts() {
		assertThatThrownBy(() -> insertRaw("RSS", null, -1, "{}")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsConfigThatIsNotAnObject() {
		assertThatThrownBy(() -> insertRaw("RSS", null, 0, "[1,2]")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertRaw("RSS", null, 0, "\"text\"")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void onlyEnabledSourcesAreListedForIngestionInCreationOrder() {
		Source first = new Source(UUID.randomUUID(), SourceType.RSS, "A", "https://a.example/feed", Map.of(), NOW);
		Source disabled = new Source(UUID.randomUUID(), SourceType.RSS, "B", "https://b.example/feed", Map.of(),
				NOW.plusSeconds(1));
		disabled.setEnabled(false);
		Source last = new Source(UUID.randomUUID(), SourceType.RSS, "C", "https://c.example/feed", Map.of(),
				NOW.plusSeconds(2));
		sources.saveAllAndFlush(List.of(last, disabled, first));

		assertThat(sources.findByEnabledTrueOrderByCreatedAtAsc()).extracting(Source::getName).containsExactly("A", "C");
		assertThat(sources.findAllByOrderByCreatedAtAsc()).extracting(Source::getName).containsExactly("A", "B", "C");
	}

}
