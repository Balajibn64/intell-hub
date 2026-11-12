-- What each user cares about. explicit_weight is what the user chose; learned_weight is what clicks and saves added
-- (it fades over time: updated_at is when it was last changed, and the decay is computed when it is read).
-- Both are per user and disappear with the user or the tag.
CREATE TABLE user_tag_weights (
    user_id         uuid             NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tag_id          uuid             NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    explicit_weight double precision NOT NULL DEFAULT 0,
    learned_weight  double precision NOT NULL DEFAULT 0,
    updated_at      timestamptz      NOT NULL,
    PRIMARY KEY (user_id, tag_id),
    CONSTRAINT user_tag_weights_explicit_range CHECK (explicit_weight >= 0 AND explicit_weight <= 1),
    CONSTRAINT user_tag_weights_learned_range CHECK (learned_weight >= 0 AND learned_weight <= 1)
);

CREATE INDEX user_tag_weights_tag_idx ON user_tag_weights (tag_id);
