-- How many provider calls were made each day, per provider. The daily budget is the sum across providers.
CREATE TABLE llm_usage (
    day      date    NOT NULL,
    provider text    NOT NULL,
    calls    integer NOT NULL DEFAULT 0,
    failures integer NOT NULL DEFAULT 0,
    PRIMARY KEY (day, provider),
    CONSTRAINT llm_usage_calls_nonnegative CHECK (calls >= 0),
    CONSTRAINT llm_usage_failures_nonnegative CHECK (failures >= 0)
);
