CREATE TABLE workflow_execution (
    id UUID PRIMARY KEY,
    definition_json JSONB NOT NULL CHECK (jsonb_typeof(definition_json) = 'object'),
    inputs_json JSONB NOT NULL CHECK (jsonb_typeof(inputs_json) = 'object'),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    current_cycle_no INTEGER NOT NULL CHECK (current_cycle_no > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    first_started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status IN ('COMPLETED', 'FAILED')) = (finished_at IS NOT NULL))
);

CREATE TABLE workflow_cycle (
    workflow_id UUID NOT NULL REFERENCES workflow_execution(id),
    cycle_no INTEGER NOT NULL CHECK (cycle_no > 0),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failed_step_id TEXT,
    error_code TEXT,
    error_message TEXT,
    PRIMARY KEY (workflow_id, cycle_no),
    CHECK ((status IN ('COMPLETED', 'FAILED')) = (finished_at IS NOT NULL))
);

ALTER TABLE workflow_execution ADD CONSTRAINT current_cycle_fk
    FOREIGN KEY (id, current_cycle_no) REFERENCES workflow_cycle(workflow_id, cycle_no)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE step_execution (
    workflow_id UUID NOT NULL REFERENCES workflow_execution(id),
    step_id TEXT NOT NULL CHECK (length(btrim(step_id)) > 0),
    action_type TEXT NOT NULL CHECK (length(btrim(action_type)) > 0),
    parameters_json JSONB NOT NULL CHECK (jsonb_typeof(parameters_json) = 'object'),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    backoff_initial_ms BIGINT NOT NULL CHECK (backoff_initial_ms > 0),
    backoff_max_ms BIGINT NOT NULL CHECK (backoff_max_ms >= backoff_initial_ms AND backoff_max_ms <= 9000000000000000),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'RETRYING')),
    eligible_at TIMESTAMPTZ,
    last_attempt_no BIGINT NOT NULL DEFAULT 0 CHECK (last_attempt_no >= 0),
    worker_id TEXT,
    lease_expires_at TIMESTAMPTZ,
    output_json JSONB,
    error_code TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (workflow_id, step_id),
    CHECK ((status = 'RUNNING' AND worker_id IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'RUNNING' AND worker_id IS NULL AND lease_expires_at IS NULL)),
    CHECK (eligible_at IS NULL OR status IN ('PENDING', 'RETRYING')),
    CHECK ((status = 'COMPLETED' AND output_json IS NOT NULL AND jsonb_typeof(output_json) = 'object')
        OR (status <> 'COMPLETED' AND output_json IS NULL))
);

ALTER TABLE workflow_cycle ADD CONSTRAINT failed_step_fk
    FOREIGN KEY (workflow_id, failed_step_id) REFERENCES step_execution(workflow_id, step_id);

CREATE TABLE step_dependency (
    workflow_id UUID NOT NULL,
    step_id TEXT NOT NULL,
    depends_on_step_id TEXT NOT NULL,
    PRIMARY KEY (workflow_id, step_id, depends_on_step_id),
    FOREIGN KEY (workflow_id, step_id) REFERENCES step_execution(workflow_id, step_id),
    FOREIGN KEY (workflow_id, depends_on_step_id) REFERENCES step_execution(workflow_id, step_id),
    CHECK (step_id <> depends_on_step_id)
);

CREATE TABLE step_attempt (
    workflow_id UUID NOT NULL,
    step_id TEXT NOT NULL,
    attempt_no BIGINT NOT NULL CHECK (attempt_no > 0),
    cycle_no INTEGER NOT NULL CHECK (cycle_no > 0),
    worker_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'ABANDONED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    result_json JSONB,
    error_code TEXT,
    error_message TEXT,
    failure_retryable BOOLEAN,
    PRIMARY KEY (workflow_id, step_id, attempt_no),
    FOREIGN KEY (workflow_id, step_id) REFERENCES step_execution(workflow_id, step_id),
    FOREIGN KEY (workflow_id, cycle_no) REFERENCES workflow_cycle(workflow_id, cycle_no),
    CHECK ((status <> 'RUNNING') = (finished_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_retryable IS NOT NULL)),
    CHECK ((status = 'COMPLETED' AND result_json IS NOT NULL AND jsonb_typeof(result_json) = 'object')
        OR (status <> 'COMPLETED' AND result_json IS NULL))
);

CREATE UNIQUE INDEX one_running_attempt_per_step
    ON step_attempt(workflow_id, step_id) WHERE status = 'RUNNING';
CREATE INDEX workflow_listing ON workflow_execution(created_at, id);
CREATE INDEX active_workflows ON workflow_execution(id) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX claimable_steps ON step_execution(eligible_at, workflow_id, step_id)
    WHERE status IN ('PENDING', 'RETRYING') AND eligible_at IS NOT NULL;
CREATE INDEX expired_leases ON step_execution(lease_expires_at, workflow_id, step_id) WHERE status = 'RUNNING';
CREATE INDEX downstream_steps ON step_dependency(workflow_id, depends_on_step_id, step_id);
CREATE INDEX attempt_cycle_history ON step_attempt(workflow_id, cycle_no, step_id, attempt_no);
CREATE INDEX attempt_time_history ON step_attempt(workflow_id, started_at DESC, step_id, attempt_no DESC);
