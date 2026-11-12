CREATE TABLE sources (
    id                   uuid PRIMARY KEY,
    type                 text        NOT NULL,
    name                 text        NOT NULL,
    url                  text        NOT NULL,
    config               jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled              boolean     NOT NULL DEFAULT true,
    last_fetched_at      timestamptz,
    last_status          text,
    last_error           text,
    consecutive_failures integer     NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    CONSTRAINT sources_type_valid CHECK (type IN ('RSS', 'HN', 'GITHUB_RELEASES', 'ARXIV', 'HUGGINGFACE')),
    CONSTRAINT sources_last_status_valid CHECK (last_status IS NULL OR last_status IN ('OK', 'FAILED')),
    CONSTRAINT sources_failures_nonnegative CHECK (consecutive_failures >= 0),
    CONSTRAINT sources_config_is_object CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT sources_type_url_unique UNIQUE (type, url)
);
