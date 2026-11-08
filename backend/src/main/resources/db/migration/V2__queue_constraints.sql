ALTER TABLE test_runs
    ADD CONSTRAINT test_runs_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    ADD CONSTRAINT test_runs_score CHECK (score BETWEEN 0 AND 100),
    ADD CONSTRAINT test_runs_duration CHECK (duration_ms >= 0);

CREATE INDEX test_runs_active_project ON test_runs(project_id)
    WHERE status IN ('QUEUED','RUNNING');
