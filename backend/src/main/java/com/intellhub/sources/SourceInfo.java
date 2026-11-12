package com.intellhub.sources;

import java.util.Map;
import java.util.UUID;

/**
 * What ingestion needs to know about a source. The config map is a read-only copy.
 *
 * @param id source id
 * @param type kind of source
 * @param name display name
 * @param url the source URL (already validated, but still untrusted at fetch time)
 * @param config type-specific settings
 */
public record SourceInfo(UUID id, SourceType type, String name, String url, Map<String, Object> config) {
}
