package com.intellhub.sources.internal;

import com.intellhub.shared.ApiException;
import com.intellhub.sources.SourceType;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Adds a few starter feeds on a fresh database. It runs only while there are no sources, so it never
 * re-adds a feed an administrator deleted and never touches an existing setup. Each entry goes through
 * the same validation as the API, and a bad entry is skipped rather than stopping startup.
 */
@Component
class DefaultSourceSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DefaultSourceSeeder.class);

	private final SourceService sources;

	private final SourcesProperties properties;

	private final ObjectMapper objectMapper;

	private final Resource seedFile;

	@Autowired
	DefaultSourceSeeder(SourceService sources, SourcesProperties properties, ObjectMapper objectMapper) {
		this(sources, properties, objectMapper, new ClassPathResource("default-sources.json"));
	}

	DefaultSourceSeeder(SourceService sources, SourcesProperties properties, ObjectMapper objectMapper,
			Resource seedFile) {
		this.sources = sources;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.seedFile = seedFile;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.seedDefaults() || sources.count() > 0) {
			return;
		}
		List<SeedEntry> entries;
		try (InputStream in = seedFile.getInputStream()) {
			entries = objectMapper.readValue(in, new TypeReference<List<SeedEntry>>() {
			});
		}
		catch (IOException | RuntimeException e) {
			log.error("Could not read the default sources file; starting without starter feeds", e);
			return;
		}
		int created = 0;
		for (SeedEntry entry : entries) {
			try {
				sources.create(entry.type(), entry.name(), entry.url(), entry.config());
				created++;
			}
			catch (ApiException | NullPointerException e) {
				log.warn("Skipping invalid default source: {}", e.getMessage());
			}
		}
		log.info("Seeded {} of {} default sources", created, entries.size());
	}

	record SeedEntry(SourceType type, String name, String url, Map<String, Object> config) {
	}

}
