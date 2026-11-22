-- The Neon (Postgres) tables tools/bench/neon_upload.py writes to (1.5.175). Run once in a new database.
CREATE TABLE bench_runs (
  id bigserial PRIMARY KEY,
  report_key text UNIQUE NOT NULL,          -- source:suite:model:folder/report.md
  started_at timestamptz,
  source text NOT NULL,                     -- workspace, actions, phone, codespace
  app_version text,
  git_commit text,
  provider text NOT NULL,
  model text NOT NULL,
  suite text NOT NULL,
  settings text,
  notes text,
  excluded text,                            -- why a run is left out of bench_summary (cut off, superseded); NULL = counted
  uploaded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON bench_runs (model, suite);
CREATE TABLE bench_results (
  id bigserial PRIMARY KEY,
  run_id bigint NOT NULL REFERENCES bench_runs(id) ON DELETE CASCADE,
  task text NOT NULL,
  attempt int NOT NULL DEFAULT 1,
  passed boolean NOT NULL,
  requests int, tool_calls int, failed_edits int, seconds int,
  ending text,
  label text NOT NULL CHECK (label IN ('done','unchecked','not done','error')),   -- as tools/bench/trust.py; error = void
  overclaim boolean NOT NULL,               -- labelled done, check failed
  underclaim boolean NOT NULL,              -- labelled not done, check passed
  tokens_in bigint, tokens_cached bigint, tokens_out bigint,
  check_said text,
  UNIQUE (run_id, task, attempt)
);
CREATE VIEW bench_summary AS
SELECT r.provider, r.model, r.suite, r.app_version, r.source,
  count(*) FILTER (WHERE x.label <> 'error') AS scored,
  count(*) FILTER (WHERE x.label <> 'error' AND x.passed) AS passed,
  count(*) FILTER (WHERE x.label = 'error') AS void,
  count(*) FILTER (WHERE x.overclaim) AS overclaims,
  count(*) FILTER (WHERE x.underclaim) AS underclaims,
  count(*) FILTER (WHERE x.label = 'done') AS labelled_done,
  count(*) FILTER (WHERE x.label = 'unchecked') AS labelled_unchecked,
  count(*) FILTER (WHERE x.label = 'not done') AS labelled_not_done,
  min(r.started_at) AS first_run, max(r.started_at) AS last_run
FROM bench_runs r JOIN bench_results x ON x.run_id = r.id
WHERE r.excluded IS NULL
GROUP BY r.provider, r.model, r.suite, r.app_version, r.source;
