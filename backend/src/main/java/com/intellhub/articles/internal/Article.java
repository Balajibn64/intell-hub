package com.intellhub.articles.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.domain.Persistable;

/** A stored article. Enrichment fields are filled in later by the enrichment module. */
@Entity
@Table(name = "articles")
@DynamicUpdate
class Article implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "source_id", nullable = false, updatable = false)
	private UUID sourceId;

	@Column(name = "canonical_url", nullable = false, updatable = false)
	private String canonicalUrl;

	@Column(name = "content_hash", nullable = false)
	private String contentHash;

	@Column(nullable = false)
	private String title;

	private String author;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "ingested_at", nullable = false, updatable = false)
	private Instant ingestedAt;

	@Column(nullable = false)
	private String excerpt;

	@Column(name = "content_sanitized", nullable = false)
	private String contentSanitized;

	private String category;

	@Column(name = "heuristic_score")
	private Double heuristicScore;

	@Column(name = "relevance_score")
	private Double relevanceScore;

	private String summary;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ArticleStatus status;

	@Column(name = "enrichment_attempts", nullable = false)
	private int enrichmentAttempts;

	@Column(name = "enriched_at")
	private Instant enrichedAt;

	@Column(name = "enriched_by")
	private String enrichedBy;

	/**
	 * The id is assigned by us, so without this Spring Data cannot tell a new article from an existing one
	 * and would run a SELECT before every insert.
	 */
	@Transient
	private boolean isNew = true;

	protected Article() {
		// for JPA
	}

	Article(UUID id, UUID sourceId, String canonicalUrl, String contentHash, String title, String author,
			Instant publishedAt, Instant ingestedAt, String excerpt, String contentSanitized) {
		this.id = id;
		this.sourceId = sourceId;
		this.canonicalUrl = canonicalUrl;
		this.contentHash = contentHash;
		this.title = title;
		this.author = author;
		this.publishedAt = publishedAt;
		this.ingestedAt = ingestedAt;
		this.excerpt = excerpt;
		this.contentSanitized = contentSanitized;
		this.status = ArticleStatus.PENDING;
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		this.isNew = false;
	}

	UUID getSourceId() {
		return sourceId;
	}

	String getCanonicalUrl() {
		return canonicalUrl;
	}

	String getContentHash() {
		return contentHash;
	}

	String getTitle() {
		return title;
	}

	String getAuthor() {
		return author;
	}

	Instant getPublishedAt() {
		return publishedAt;
	}

	Instant getIngestedAt() {
		return ingestedAt;
	}

	String getExcerpt() {
		return excerpt;
	}

	String getContentSanitized() {
		return contentSanitized;
	}

	ArticleStatus getStatus() {
		return status;
	}

	int getEnrichmentAttempts() {
		return enrichmentAttempts;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof Article other && id != null && Objects.equals(id, other.id));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

}
