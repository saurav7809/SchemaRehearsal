CREATE TABLE project_ci_tokens (
    project_id bigint PRIMARY KEY REFERENCES projects ON DELETE CASCADE,
    baseline_id bigint NOT NULL REFERENCES project_baselines ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX project_ci_tokens_baseline ON project_ci_tokens(baseline_id);

ALTER TABLE test_runs ADD COLUMN ci_submission boolean NOT NULL DEFAULT false;
