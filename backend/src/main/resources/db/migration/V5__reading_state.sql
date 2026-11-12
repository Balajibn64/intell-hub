-- What each user has saved and clicked. Both are per user, and both disappear with the user or the article.
CREATE TABLE saved_articles (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    article_id uuid        NOT NULL REFERENCES articles (id) ON DELETE CASCADE,
    saved_at   timestamptz NOT NULL,
    PRIMARY KEY (user_id, article_id)
);

CREATE INDEX saved_articles_user_saved_idx ON saved_articles (user_id, saved_at DESC);
CREATE INDEX saved_articles_article_idx ON saved_articles (article_id);

-- The raw signal that later milestones learn from.
CREATE TABLE article_events (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    article_id uuid        NOT NULL REFERENCES articles (id) ON DELETE CASCADE,
    type       text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT article_events_type_valid CHECK (type IN ('CLICK', 'SAVE', 'UNSAVE'))
);

CREATE INDEX article_events_user_created_idx ON article_events (user_id, created_at);
CREATE INDEX article_events_article_idx ON article_events (article_id);

-- Search covers the title, the stored excerpt and the summary. The V4 index covered only title and summary,
-- which would leave search with titles alone until summaries exist. Queries must use exactly this expression
-- for the index to be used.
DROP INDEX articles_search_idx;
CREATE INDEX articles_search_idx ON articles
    USING GIN (to_tsvector('english', title || ' ' || excerpt || ' ' || coalesce(summary, '')));
