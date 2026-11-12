CREATE TABLE articles (
    id                  uuid PRIMARY KEY,
    -- Deleting a source deletes its articles. Disabling a source is the normal way to stop it.
    source_id           uuid             NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    canonical_url       text             NOT NULL,
    content_hash        text             NOT NULL,
    title               text             NOT NULL,
    author              text,
    published_at        timestamptz      NOT NULL,
    ingested_at         timestamptz      NOT NULL,
    excerpt             text             NOT NULL DEFAULT '',
    content_sanitized   text             NOT NULL DEFAULT '',
    category            text,
    heuristic_score     double precision,
    relevance_score     double precision,
    summary             text,
    status              text             NOT NULL DEFAULT 'PENDING',
    enrichment_attempts integer          NOT NULL DEFAULT 0,
    enriched_at         timestamptz,
    enriched_by         text,
    CONSTRAINT articles_canonical_url_unique UNIQUE (canonical_url),
    -- The same content under another URL (a mirror, or two feeds carrying one story) is one article.
    -- Items with little text include their URL in the hash, so unrelated short items never collide.
    CONSTRAINT articles_content_hash_unique UNIQUE (content_hash),
    CONSTRAINT articles_status_valid CHECK (status IN ('EXCERPT_ONLY', 'PENDING', 'ENRICHING', 'ENRICHED', 'FAILED')),
    CONSTRAINT articles_category_valid CHECK (category IS NULL
        OR category IN ('AI_ML', 'SOFTWARE_ENGINEERING', 'INDUSTRY_STARTUPS')),
    CONSTRAINT articles_attempts_nonnegative CHECK (enrichment_attempts >= 0),
    -- Size limits as a backstop to the application checks.
    CONSTRAINT articles_url_length CHECK (char_length(canonical_url) <= 2048),
    CONSTRAINT articles_title_length CHECK (char_length(title) <= 300),
    CONSTRAINT articles_author_length CHECK (author IS NULL OR char_length(author) <= 200),
    CONSTRAINT articles_excerpt_length CHECK (char_length(excerpt) <= 500),
    CONSTRAINT articles_content_length CHECK (char_length(content_sanitized) <= 50000)
);

CREATE INDEX articles_status_ingested_idx ON articles (status, ingested_at);
CREATE INDEX articles_published_idx ON articles (published_at DESC);
CREATE INDEX articles_source_idx ON articles (source_id);
CREATE INDEX articles_search_idx ON articles
    USING GIN (to_tsvector('english', title || ' ' || coalesce(summary, '')));
