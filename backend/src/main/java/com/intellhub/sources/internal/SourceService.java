package com.intellhub.sources.internal;

import com.intellhub.shared.ApiException;
import com.intellhub.sources.FetchResult;
import com.intellhub.sources.SourceCatalog;
import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class SourceService implements SourceCatalog {

	static final int MAX_NAME_LENGTH = 120;

	static final int MAX_ERROR_LENGTH = 500;

	private static final Logger log = LoggerFactory.getLogger(SourceService.class);

	private final SourceRepository sources;

	private final ObjectMapper objectMapper;

	private final Clock clock;

	SourceService(SourceRepository sources, ObjectMapper objectMapper, Clock clock) {
		this.sources = sources;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	// ---- published API for other modules ----

	@Override
	@Transactional(readOnly = true)
	public List<SourceInfo> enabledSources() {
		return sources.findByEnabledTrueOrderByCreatedAtAsc().stream()
				.map(s -> new SourceInfo(s.getId(), s.getType(), s.getName(), s.getUrl(),
						Collections.unmodifiableMap(new LinkedHashMap<>(s.getConfig()))))
				.toList();
	}

	@Override
	@Transactional
	public void recordFetchResult(UUID sourceId, FetchResult result) {
		int updated = result.success()
				? sources.markSuccess(sourceId, clock.instant(), FetchStatus.OK)
				: sources.markFailure(sourceId, clock.instant(), FetchStatus.FAILED, cleanError(result.error()));
		if (updated == 0) {
			log.debug("Ignoring fetch result for unknown source {}", sourceId);
		}
	}

	// ---- administration ----

	@Transactional(readOnly = true)
	List<Source> listAll() {
		return sources.findAllByOrderByCreatedAtAsc();
	}

	@Transactional
	Source create(SourceType type, String name, String url, Map<String, Object> config) {
		String cleanName = cleanName(name);
		SourceUrlValidator.validate(url);
		Map<String, Object> cleanConfig = cleanConfig(config);
		String normalizedUrl = normalizeUrl(url);
		if (sources.existsByTypeAndUrl(type, normalizedUrl)) {
			throw exists();
		}
		try {
			return sources.saveAndFlush(
					new Source(UUID.randomUUID(), type, cleanName, normalizedUrl, cleanConfig, clock.instant()));
		}
		catch (DataIntegrityViolationException e) {
			// Lost a race with a concurrent create. Other constraint failures are real errors.
			String message = e.getMostSpecificCause().getMessage();
			if (message != null && message.contains("sources_type_url_unique")) {
				throw exists();
			}
			throw e;
		}
	}

	/** Changes only the fields that are not null. The type and URL are never changed. */
	@Transactional
	Source update(UUID id, String name, Boolean enabled, Map<String, Object> config) {
		Source source = sources.findById(id).orElseThrow(SourceService::notFound);
		if (name != null) {
			source.rename(cleanName(name));
		}
		if (enabled != null) {
			source.setEnabled(enabled);
		}
		if (config != null) {
			source.replaceConfig(cleanConfig(config));
		}
		return sources.saveAndFlush(source);
	}

	@Transactional
	void delete(UUID id) {
		Source source = sources.findById(id).orElseThrow(SourceService::notFound);
		sources.delete(source);
	}

	@Transactional(readOnly = true)
	long count() {
		return sources.count();
	}

	// ---- helpers ----

	private String cleanName(String name) {
		String stripped = name == null ? "" : name.strip();
		if (stripped.isEmpty() || stripped.length() > MAX_NAME_LENGTH || stripped.chars().anyMatch(Character::isISOControl)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
					"Name must be 1 to " + MAX_NAME_LENGTH + " characters without control characters");
		}
		return stripped;
	}

	private Map<String, Object> cleanConfig(Map<String, Object> config) {
		Map<String, Object> value = config == null ? Map.of() : config;
		int bytes = objectMapper.writeValueAsBytes(value).length;
		SourceConfigValidator.validate(value, bytes);
		return value;
	}

	/** Lowercases the scheme and host, so the same feed cannot be added twice with different case. */
	static String normalizeUrl(String url) {
		int schemeEnd = url.indexOf("://");
		int authorityEnd = url.length();
		for (char c : new char[] { '/', '?', '#' }) {
			int i = url.indexOf(c, schemeEnd + 3);
			if (i >= 0 && i < authorityEnd) {
				authorityEnd = i;
			}
		}
		return url.substring(0, authorityEnd).toLowerCase(java.util.Locale.ROOT) + url.substring(authorityEnd);
	}

	private static String cleanError(String error) {
		if (error == null || error.isBlank()) {
			return "Unknown error";
		}
		String flat = error.replaceAll("\\p{Cntrl}", " ").strip();
		return flat.length() > MAX_ERROR_LENGTH ? flat.substring(0, MAX_ERROR_LENGTH) : flat;
	}

	private static ApiException exists() {
		return new ApiException(HttpStatus.CONFLICT, "SOURCE_EXISTS", "A source with this type and URL already exists");
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "Source not found");
	}

}
