package com.intellhub.sources.internal;

import com.intellhub.sources.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A configured content source. The type and URL never change after creation. */
@Entity
@Table(name = "sources")
class Source {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private SourceType type;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, updatable = false)
	private String url;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> config = new LinkedHashMap<>();

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "last_fetched_at")
	private Instant lastFetchedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_status")
	private FetchStatus lastStatus;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "consecutive_failures", nullable = false)
	private int consecutiveFailures;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Source() {
		// for JPA
	}

	Source(UUID id, SourceType type, String name, String url, Map<String, Object> config, Instant createdAt) {
		this.id = id;
		this.type = type;
		this.name = name;
		this.url = url;
		this.config = new LinkedHashMap<>(config);
		this.enabled = true;
		this.createdAt = createdAt;
	}

	UUID getId() {
		return id;
	}

	SourceType getType() {
		return type;
	}

	String getName() {
		return name;
	}

	String getUrl() {
		return url;
	}

	Map<String, Object> getConfig() {
		return config;
	}

	boolean isEnabled() {
		return enabled;
	}

	Instant getLastFetchedAt() {
		return lastFetchedAt;
	}

	FetchStatus getLastStatus() {
		return lastStatus;
	}

	String getLastError() {
		return lastError;
	}

	int getConsecutiveFailures() {
		return consecutiveFailures;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	void rename(String newName) {
		this.name = newName;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	void replaceConfig(Map<String, Object> newConfig) {
		this.config = new LinkedHashMap<>(newConfig);
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof Source other && id != null && Objects.equals(id, other.id));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

}
