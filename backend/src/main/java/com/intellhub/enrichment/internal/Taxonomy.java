package com.intellhub.enrichment.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The curated tags, each with a category and keywords, read from taxonomy.json. It powers the cheap
 * pre-filter and the fake provider. Keywords match whole words only, so "rust" does not match "trust".
 */
@Component
class Taxonomy {

	record TagDefinition(String name, String category, List<String> keywords) {
	}

	/** The tags whose keywords were found, with how many distinct keywords each matched. */
	record Matches(Map<String, Integer> hitsByTag, int distinctKeywordsInTitle, int distinctKeywordsInText) {
	}

	private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9][a-z0-9+#.-]{0,29}$");

	private final List<TagDefinition> tags;

	private final Map<TagDefinition, List<Pattern>> patterns = new LinkedHashMap<>();

	@Autowired
	Taxonomy(ObjectMapper objectMapper) throws IOException {
		this(load(objectMapper));
	}

	Taxonomy(List<TagDefinition> tags) {
		this.tags = List.copyOf(tags);
		Set<String> seen = new LinkedHashSet<>();
		for (TagDefinition tag : this.tags) {
			if (!VALID_NAME.matcher(tag.name()).matches() || !seen.add(tag.name())) {
				throw new IllegalStateException("Invalid or duplicate taxonomy tag");
			}
			List<Pattern> compiled = new ArrayList<>();
			for (String keyword : tag.keywords()) {
				compiled.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword.toLowerCase(Locale.ROOT))
						+ "(?![\\p{L}\\p{N}])"));
			}
			patterns.put(tag, compiled);
		}
	}

	private static List<TagDefinition> load(ObjectMapper objectMapper) throws IOException {
		try (InputStream in = new ClassPathResource("taxonomy.json").getInputStream()) {
			return objectMapper.readValue(in, new TypeReference<List<TagDefinition>>() {
			});
		}
	}

	List<TagDefinition> tags() {
		return tags;
	}

	Set<String> names() {
		Set<String> names = new LinkedHashSet<>();
		tags.forEach(tag -> names.add(tag.name()));
		return names;
	}

	Matches match(String title, String text) {
		String lowerTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
		String lowerText = text == null ? "" : text.toLowerCase(Locale.ROOT);
		Map<String, Integer> hits = new LinkedHashMap<>();
		int inTitle = 0;
		int inText = 0;
		for (Map.Entry<TagDefinition, List<Pattern>> entry : patterns.entrySet()) {
			int tagHits = 0;
			for (Pattern pattern : entry.getValue()) {
				boolean titleHit = pattern.matcher(lowerTitle).find();
				boolean textHit = pattern.matcher(lowerText).find();
				if (titleHit) {
					inTitle++;
				}
				if (textHit) {
					inText++;
				}
				if (titleHit || textHit) {
					tagHits++;
				}
			}
			if (tagHits > 0) {
				hits.put(entry.getKey().name(), tagHits);
			}
		}
		return new Matches(hits, inTitle, inText);
	}

	/** A score from 0 to 1 that rises with every distinct keyword found, title hits counting three times. */
	double score(String title, String text) {
		Matches matches = match(title, text);
		double raw = 3.0 * matches.distinctKeywordsInTitle() + matches.distinctKeywordsInText();
		return 1.0 - Math.exp(-raw / 4.0);
	}

	/** The category with the most keyword hits, or null if nothing matched. Ties go to the first listed. */
	String categoryFor(Matches matches) {
		Map<String, Integer> byCategory = new LinkedHashMap<>();
		for (TagDefinition tag : tags) {
			Integer hits = matches.hitsByTag().get(tag.name());
			if (hits != null) {
				byCategory.merge(tag.category(), hits, Integer::sum);
			}
		}
		return byCategory.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

}
