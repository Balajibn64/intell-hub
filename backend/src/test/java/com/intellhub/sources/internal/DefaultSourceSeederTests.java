package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.sources.SourceType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.TestConstructor;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DefaultSourceSeederTests {

	private final SourceService service;

	private final SourceRepository repository;

	private final ObjectMapper objectMapper;

	DefaultSourceSeederTests(SourceService service, SourceRepository repository, ObjectMapper objectMapper) {
		this.service = service;
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	@BeforeEach
	void clean() {
		repository.deleteAll();
	}

	private DefaultSourceSeeder seeder(boolean enabled, Resource file) {
		return new DefaultSourceSeeder(service, new SourcesProperties(enabled), objectMapper, file);
	}

	private static Resource json(String content) {
		return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void theBundledStarterFeedsAreAllValidPublicRssSources() {
		seeder(true, new ClassPathResource("default-sources.json")).run(null);

		List<Source> seeded = repository.findAllByOrderByCreatedAtAsc();
		assertThat(seeded).hasSize(6);
		assertThat(seeded).allSatisfy(s -> {
			assertThat(s.getType()).isEqualTo(SourceType.RSS);
			assertThat(s.getUrl()).startsWith("https://");
			assertThat(s.isEnabled()).isTrue();
			assertThat(SourceUrlValidator.problem(s.getUrl())).isNull();
		});
		assertThat(seeded).extracting(Source::getName).doesNotHaveDuplicates();
	}

	@Test
	void doesNothingWhenSwitchedOff() {
		seeder(false, new ClassPathResource("default-sources.json")).run(null);

		assertThat(repository.count()).isZero();
	}

	@Test
	void doesNothingWhenSourcesAlreadyExist() {
		service.create(SourceType.RSS, "Mine", "https://mine.example/feed", Map.of());

		seeder(true, new ClassPathResource("default-sources.json")).run(null);

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void runningTwiceDoesNotAddOrRestoreAnything() {
		seeder(true, new ClassPathResource("default-sources.json")).run(null);
		Source removed = repository.findAllByOrderByCreatedAtAsc().get(0);
		service.delete(removed.getId());

		seeder(true, new ClassPathResource("default-sources.json")).run(null);

		assertThat(repository.count()).isEqualTo(5);
		assertThat(repository.findAll()).extracting(Source::getUrl).doesNotContain(removed.getUrl());
	}

	@Test
	void badEntriesAreSkippedAndTheRestAreCreated() {
		Resource file = json("""
				[
				  { "type": "RSS", "name": "Good", "url": "https://good.example/feed" },
				  { "type": "RSS", "name": "Internal", "url": "http://169.254.169.254/latest" },
				  { "type": "RSS", "name": "", "url": "https://noname.example/feed" },
				  { "type": "RSS", "name": "Missing url" },
				  { "type": "RSS", "name": "Also good", "url": "https://good2.example/feed" }
				]
				""");

		seeder(true, file).run(null);

		assertThat(repository.findAllByOrderByCreatedAtAsc()).extracting(Source::getName)
				.containsExactly("Good", "Also good");
	}

	@Test
	void anUnreadableOrMalformedFileNeverStopsStartup() {
		seeder(true, json("this is not json")).run(null);
		seeder(true, json("{\"not\": \"a list\"}")).run(null);
		seeder(true, new ClassPathResource("does-not-exist.json")).run(null);

		assertThat(repository.count()).isZero();
	}

}
