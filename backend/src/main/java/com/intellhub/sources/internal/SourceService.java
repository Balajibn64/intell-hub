package com.intellhub.sources.internal;

import com.intellhub.shared.ApiException;
import com.intellhub.shared.PublicUrlPolicy;
import com.intellhub.shared.Text;
import com.intellhub.sources.FetchResult;
import com.intellhub.sources.SourceCatalog;
import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.time.Clock;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
				.map(s -> new SourceInfo(s.getId(), s.getType(), s.getName(), s.getUrl(), readOnlyCopy(s.getConfig())))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> names(Collection<UUID> sourceIds) {
		if (sourceIds == null || sourceIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new LinkedHashMap<>();
		sources.findAllById(sourceIds).forEach(source -> names.put(source.getId(), source.getName()));
		return names;
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
		if (type == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Type is required");
		}
		String cleanName = cleanName(name);
		SourceUrlValidator.validate(type, url);
		Map<String, Object> cleanConfig = cleanConfig(type, config);
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
			source.replaceConfig(cleanConfig(source.getType(), config));
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

	private Map<String, Object> cleanConfig(SourceType type, Map<String, Object> config) {
		Map<String, Object> value = config == null ? Map.of() : config;
		int bytes = objectMapper.writeValueAsBytes(value).length;
		SourceConfigValidator.validate(type, value, bytes);
		return value;
	}

	/** See {@link PublicUrlPolicy#normalize(String)}; the URL must already have been validated. */
	static String normalizeUrl(String url) {
		return PublicUrlPolicy.normalize(url);
	}

	/** A deep, read-only copy, so a caller cannot change nested values of the stored config. */
	private static Map<String, Object> readOnlyCopy(Map<String, Object> config) {
		Map<String, Object> copy = new LinkedHashMap<>();
		config.forEach((key, value) -> copy.put(key, readOnlyValue(value)));
		return Collections.unmodifiableMap(copy);
	}

	private static Object readOnlyValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copy = new LinkedHashMap<>();
			map.forEach((key, nested) -> copy.put(String.valueOf(key), readOnlyValue(nested)));
			return Collections.unmodifiableMap(copy);
		}
		if (value instanceof List<?> list) {
			return list.stream().map(SourceService::readOnlyValue).toList();
		}
		return value;
	}

	/** Flattens line breaks (including Unicode ones) and cuts to the limit without splitting a surrogate pair. */
	private static String cleanError(String error) {
		if (error == null || error.isBlank()) {
			return "Unknown error";
		}
		String flat = error.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", " ").strip();
		if (flat.length() <= MAX_ERROR_LENGTH) {
			return flat;
		}
		return Text.cut(flat, MAX_ERROR_LENGTH);
	}

	private static ApiException exists() {
		return new ApiException(HttpStatus.CONFLICT, "SOURCE_EXISTS", "A source with this type and URL already exists");
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "Source not found");
	}

}
