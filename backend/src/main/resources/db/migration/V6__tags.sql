-- Tags describe articles. TAXONOMY tags are the curated set (seeded at startup); FREE tags come from the
-- model and are constrained and capped, because they are the one piece of text a model chooses freely.
CREATE TABLE tags (
    id         uuid PRIMARY KEY,
    name       text        NOT NULL,
    kind       text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT tags_name_unique UNIQUE (name),
    CONSTRAINT tags_name_format CHECK (name ~ '^[a-z0-9][a-z0-9+#.-]{0,29}$'),
    CONSTRAINT tags_kind_valid CHECK (kind IN ('TAXONOMY', 'FREE'))
);

CREATE TABLE article_tags (
    article_id uuid NOT NULL REFERENCES articles (id) ON DELETE CASCADE,
    tag_id     uuid NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    origin     text NOT NULL,
    PRIMARY KEY (article_id, tag_id),
    CONSTRAINT article_tags_origin_valid CHECK (origin IN ('HEURISTIC', 'LLM'))
);

CREATE INDEX article_tags_tag_idx ON article_tags (tag_id, article_id);

-- When an enrichment worker claimed an article, and when a reader asked for it to be enriched.
ALTER TABLE articles ADD COLUMN claimed_at timestamptz;
ALTER TABLE articles ADD COLUMN enrichment_requested_at timestamptz;
