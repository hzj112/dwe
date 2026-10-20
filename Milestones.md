# Distributed Workflow Engine — Milestones

This roadmap covers the design phase, required implementation, delivery verification, and optional improvements. It brings the completed core milestones and proposed extensions into one roadmap. Historical planning and verification records remain in [Plan.md](Plan.md); [PROJECT_DESCRIPTION.md](PROJECT_DESCRIPTION.md) defines the project scope.

Design references: [high-level design](DESIGN.md) and [Detailed Design](DETAILED_DESIGN.md). Setup, examples, and verification commands are in [README.md](README.md).

## Status and Priorities

Milestone 1 records the design work completed before implementation. Milestones 2–7 are implemented and committed, with milestone 7 committed as `c403b14`. The separately approved optional sub-milestone 10a adds observability and failure diagnosis; its checkpoint is below. The final provider-records application demo is also implemented and locally verified. Other optional milestones and the remaining scope of milestone 10 have not started.

Priority order:

1. Complete and demonstrate the assignment requirements.
2. Strengthen reliability and operational safety.
3. Improve measured scalability.
4. Improve usability and add workflow capabilities.

The milestones below record completed core work and proposed extensions. Listing an extension does not authorize its implementation. Security work must precede any shared or public deployment, regardless of its roadmap number.

## Core Milestones 1–7

### Milestone 1 — Design Phase

**Outcome:** Agree on the system architecture, execution guarantees, and implementation boundaries before writing application code.

**Scope:**

- Translate the assignment into required capabilities, optional features, and delivery criteria.
- Select Java 21, Maven, Spring Boot, PostgreSQL, and Docker Compose; define API/coordinator, persistence, worker, and business-handler responsibilities.
- Define the DAG format, handler contract, validation rules, REST API, and persistence model.
- Specify workflow, step, cycle, and attempt lifecycles, including dependency readiness, automatic backoff, terminal failure, and manual retry.
- Design atomic claims, leases, fencing, lock ordering, ownership-loss handling, and uncertain-outcome reconciliation.
- Record alternatives, tradeoffs, failure modes, at-least-once semantics, idempotency assumptions, scalability limits, and deferred capabilities.
- Establish the implementation sequence, verification strategy, and local environment prerequisites.

**Acceptance:** The reviewed design explains how to preserve the distributed-systems invariants and provides concrete contracts for implementation. Scope and known limitations are explicit, and the first coding milestone is bounded and reviewable.

**Status:** Completed through the architecture and contract reviews recorded in [DESIGN.md](DESIGN.md) and [Plan.md](Plan.md). [Detailed Design](DETAILED_DESIGN.md) preserves the technical contracts and decision rationale.

### Milestone 2 — Minimal End-to-End Implementation

**Outcome:** Submit and execute a sequential workflow with durable state and a separate worker process.

**Scope:**

- Scaffold the Maven/Spring Boot application, Maven Wrapper, API/worker profiles, Docker image, and Compose deployment.
- Create the five-table Flyway schema, constraints, indexes, and PostgreSQL integration-test foundation.
- Implement submission validation, effective retry defaults, handler registration, and the `ECHO` and `DELAY` demonstration handlers.
- Provide submission, execution detail/status, and individual step status APIs.
- Implement the transactional store, workflow guard, atomic claims, leases, fencing, outcome persistence, dependency advancement, and a bounded worker runtime.
- Verify sequential execution and retained results after application restart.

**Acceptance:** A valid sequential workflow completes through the HTTP API, and its status/results survive restart. Invalid definitions and unknown actions are rejected. Competing or stale ownership writes cannot corrupt state. API/coordinator and worker profiles start independently, and Maven verification passes against actual PostgreSQL.

**Status:** Completed and committed as `91600a6`. Multi-worker, recovery, complete retry behavior, and remaining API acceptance are covered by later milestones.

### Milestone 3 — Parallel DAG Execution and Competing Workers

**Outcome:** Demonstrate correct execution across multiple workers.

**Scope:**

- Make two worker containers the default local deployment, each with bounded concurrency.
- Add a parallel sample with branches and a join.
- Verify eligibility and competing claims while retaining PostgreSQL coordination and workflow-before-step lock ordering.
- Test workflow failure racing with claims and independent step completion.

**Acceptance:** Independent branches overlap; joins wait for every dependency; competing workers create only one valid claim per step. No new claim succeeds after workflow failure commits, and late success cannot reopen a failed workflow.

**Status:** Completed and committed as `1136fc8`. This established normal distributed execution before crash and partition recovery verification.

### Milestone 4 — Crash Recovery and Connectivity Failures

**Outcome:** Interrupted work recovers automatically without accepting stale results.

**Scope:**

- Add periodic expired-lease recovery, including running attempts on failed workflows.
- Recheck expiry after acquiring locks, using current database time.
- Atomically mark expired attempts `ABANDONED`, clear ownership, and schedule backoff or terminal failure according to the persisted allowance. Abandoned claims consume attempts.
- Verify local ownership deadlines, transient renewal failures, explicit rejection, and uncertain claim/outcome responses.
- Verify graceful draining and ensure handlers that ignore interruption retain their capacity until they exit.

**Acceptance:** Killing a worker permits replacement execution after expiry and backoff. A concurrent renewal prevents mistaken abandonment. Stale results cannot change newer state. Database connectivity loss stops unsafe reporting, and restoration permits recovery. Concurrent recovery passes are harmless.

**Status:** Completed and committed as `457e0ec`. Evidence includes controlled PostgreSQL race tests and actual worker death, worker network isolation, and database pause through [verify-recovery.py](scripts/verify-recovery.py).

### Milestone 5 — Automatic Retries and Manual Workflow Retry

**Outcome:** Users can recover failed workflows while preserving completed work and history.

**Scope:**

- Add the stateless `FAIL_UNTIL_ATTEMPT` handler, driven by persisted lifetime attempt numbers.
- Complete automatic retry verification: capped exponential backoff, total-attempt limits, permanent failures, unexpected exceptions, and restart persistence.
- Implement `POST /api/v1/executions/{id}/retry`.
- Retry only failed executions with no unfinished attempts. Preserve completed steps and outputs; reset failed steps in a new cycle with fresh allowances.
- Preserve prior cycles, attempts, inputs, definition, policies, and increasing fencing counters.

**Acceptance:** A workflow can fail, be manually retried, and complete without rerunning successful predecessors. Concurrent retry requests create only one new cycle. Unfinished attempts block retry with `409`. Retry policies and history survive restart.

**Status:** Completed and committed as `8bd788e`. [verify-retries.py](scripts/verify-retries.py) covers the examples and an API/worker restart during persisted backoff.

### Milestone 6 — Listing and Execution History APIs

**Outcome:** Users can inspect executions and understand failures through the API.

**Scope:**

- Implement `GET /api/v1/executions`, `GET /api/v1/executions/{id}/cycles`, and `GET /api/v1/executions/{id}/attempts`.
- Add bounded pages, stable keyset ordering, optional filters, and resource/filter-bound cursors. The default page size is 50; the maximum is 200.
- Expose cycle outcomes, worker identities, timestamps, stored results, and failure classifications.
- Keep all inspection routes read-only and preserve prior history after manual retry.

**Acceptance:** Pagination handles timestamp ties and multiple cycles without ordering errors. Invalid filters/cursors return useful errors. History distinguishes reported failures, abandonment, and steps blocked by workflow failure. Reads do not modify stored state; separate pages do not promise a frozen snapshot.

**Status:** Completed and committed as `a12f473`. [verify-history.py](scripts/verify-history.py) exercises public listing/history routes and retained prior-cycle records.

### Milestone 7 — Reproducible Delivery and Final Verification

**Outcome:** A reviewer can reproduce the required behavior from a fresh checkout.

**Scope:**

- Provide runnable sequential, parallel, automatic-retry, manual-retry, permanent-failure, and recovery examples.
- Demonstrate worker competition, crash recovery, connectivity failure, and persistence across restarts.
- Verify Maven builds, Docker image builds, and the exact `docker-compose up` startup path.
- Verify application/database restarts and full container recreation with the database volume retained, including queued-work resumption.
- Complete the required README and design content, documenting actual guarantees, tradeoffs, failure modes, limitations, and prioritized future work.

**Acceptance:** Documented commands reproduce every required capability. [verify-delivery.py](scripts/verify-delivery.py) uses ordinary engine defaults and checks branch overlap, join ordering, exclusive recorded attempts, retained terminal records, and resumed queued work. Fault/retry/history scripts provide the complementary scenarios.

**Status:** Completed and committed as `c403b14`. The verification record below describes the candidate tested before that commit.

Milestone 7 is the assignment-complete boundary. Optional extensions must not delay the required delivery.

## Recorded Verification

These are historical checkpoint results consolidated from the milestone records and [Plan.md](Plan.md), not new test runs performed during the documentation reorganization.

| Milestone | Unit tests passed | PostgreSQL integration tests passed | Additional evidence |
| --- | --- | --- | --- |
| 2 | 24 | 21 | Image/Compose startup, sequential HTTP execution, and application restart retention. |
| 3 | 24 | 30 | Parallel/four-branch workflows, join ordering, worker competition, and capacity bounds. |
| 4 | 29 | 47 | Worker crash, partition, and database pause with durable abandonment/replacement history. |
| 5 | 42 | 70 | Automatic/manual retries, permanent errors, and restart during persisted backoff. |
| 6 | 56 | 107 | Paginated listing/history, filters/errors, and retained prior history. |
| 7 | 56 | 107 | Clean Maven verification and all four delivery/recovery/retry/history scripts. |

The recorded suites passed without failures, errors, or skips. Final verification on 2026-10-18 used a fresh local clone of `a12f473` with the candidate milestone 7 files applied before their later commit as `c403b14`. Ordinary Maven/Docker caches were reused; original build artifacts, `.env`, and IDE files were not copied. Temporary verification resources were cleaned up, and the normal local Compose project was preserved.

This establishes the recorded local verification boundary, not production load capacity, database high availability, another platform, or an empty-cache build. Re-run the documented checks when implementation changes affect the tested behavior.

## Optional Milestones 8–21

These are proposed extensions, not requirements for submission. Each receives its own focused design before implementation, particularly where states or public contracts change. Their original numbering and order are retained for discussion.

### Milestone 10a — Observability and Failure Diagnosis

**Outcome:** A reviewer can inspect a provisioned dashboard, distinguish capacity pressure from worker loss, database failure, and stalled coordination, and verify recovery against durable history.

**Scope:**

- Add best-effort process metrics and structured lifecycle events, with bounded labels and commit-aware transition accounting.
- Sample durable state through periodic read-only aggregates using a separate bounded connection pool/thread; cache results and expose freshness/errors.
- Add an optional Compose overlay with internal management endpoints, independent worker discovery, Prometheus, Grafana provisioning, and tested alert rules. Keep ordinary startup and public API contracts unchanged.
- Provide a dashboard/runbook and isolated scenarios for saturation, worker death, coordinator stall with UP health, database outage, monitoring outage, and scale-out discovery.
- Verify rollback/duplicate/stale-result accounting, time-derived eligibility, cached reads during query failure, and monitoring failure isolation.

**Acceptance:** A fresh monitored deployment provisions its dashboard without manual setup, discovers each worker separately, suppresses stale queue observations, and exposes actionable failure signals. Monitoring failure does not stop workflow execution. Existing execution/lease/retry invariants continue to pass.

**Status:** Implemented and locally verified on 2026-10-19. Clean Maven verification passed 60 unit tests and 113 PostgreSQL integration tests without failures/errors/skips. Prometheus configuration and fresh/stale alert-rule tests passed. The isolated observability script passed provisioning, independent scrapes, saturation, worker death/replacement, stalled promotion with UP API health and a firing progress alert, database pause/stale-data suppression/alerting, monitoring-backend outage isolation, and three-worker discovery. The original delivery script also passed ordinary Compose startup, examples, and application/database/container recreation persistence. The Grafana dashboard was visually inspected, and all 25 dashboard expressions executed successfully against Prometheus.

**Findings and limits:** Successful coordinator passes can skip locked workflows, so a persistent awaiting-promotion alert checks progress independently of loop activity. Retained metric gauge suppliers prevent worker capacity from becoming NaN after garbage collection. The small demonstration workload showed an approximately 16ms p95 histogram estimate for successful database collection; this is not a load benchmark or production SLO. Counter crash gaps, sampling lag, Compose-specific deployment, and the single designated sampler assumption are documented in the runbook. No later milestone has started.

**Boundary:** This is the approved observability subset of milestone 10, using in-memory instrumentation plus aggregate database sampling. No summary tables, CDC, event-projection pipeline, distributed tracing, centralized logs, external notifications, backup/restore, or Kubernetes deployment are introduced. These remain separately scoped follow-ups. See [OBSERVABILITY.md](OBSERVABILITY.md).

### Final Application Demo — Provider Records Validation

**Outcome:** A realistic application consumes DWE through existing contracts, demonstrating parallel business processing, persisted results, retries, and worker-crash recovery. A standalone HTTP client presents the report and exports cleaned records outside the engine.

**Scope:** Five registered demo handlers execute load → parallel normalization/validation → summary → finalization. Synthetic records produce four cleaned records and three findings from five input rows. Exact duplicates are removed, conflicting IDs are retained/reported, and invalid business fields become findings. Separate retry/crash fixtures enable explicit first-attempt faults. The external client supports submit, monitor, local JSON export, and re-export of an existing execution. An isolated Compose walkthrough verifies all three modes against equivalent business outputs.

**Acceptance:** The supplied dataset produces the documented report; branch overlap/join ordering, retry backoff, durable output retrieval, and actual worker death/lease recovery are verified. Core engine, worker runtime, persistence, API server, schema, and monitoring are unchanged. No special report API is introduced.

**Status:** Implemented and locally verified on 2026-10-19. Clean Maven verification passed 94 unit tests and 117 PostgreSQL integration tests without failures/errors/skips. All 12 client tests passed. The final monitored provider walkthrough and original delivery/restart checks passed. No additional optional milestone has started.

**Design, operation, and limits:** [PROVIDER_RECORDS_VALIDATION_DEMO.md](PROVIDER_RECORDS_VALIDATION_DEMO.md) owns the complete business rules, output contracts, CLI commands, fault scenarios, verification evidence, and known limits. This bounded application example does not implement the broader optional developer/operator-usability roadmap.

### Optional roadmap

| Milestone | Features and improvements | Completion evidence |
| --- | --- | --- |
| **8 — Safe repeated API commands** | Durable idempotency keys for submission and manual retry, including payload mismatch detection and uncertain-response reconciliation. | Repeated requests after lost responses produce one execution or retry cycle. |
| **9 — Execution deadlines** | Optional per-step timeouts, explicit timeout classification, retry behavior, and cooperative cancellation. Keep timeout separate from ownership lease duration. | Healthy heartbeats cannot extend a configured execution deadline; late outcomes cannot replace the recorded timeout. |
| **10 — Operational reliability** | Structured diagnostic events, metrics and alerts for stuck work/recovery/renewals, readiness checks, CI verification, dependency checks, and tested database backup/restore procedures. | Failures can be diagnosed and persisted execution state can be restored using documented procedures. |
| **11 — Measured throughput improvements** | Establish load baselines; bound coordinator/recovery scans; improve candidate traversal, indexes, polling behavior, and connection budgets. Check starvation across workflows. | Repeatable benchmarks show improvement while all ownership and lifecycle tests pass. |
| **12 — Horizontal application scaling** | Verify multiple API/coordinator instances, independent worker scaling, and optional worker capabilities for different action types. Separate coordinator deployment only when useful. | Additional instances preserve correctness and provide measured capacity or availability benefits. |
| **13 — Storage and workload growth** | Define payload limits; add terminal-history retention/archival and optional external storage for large results. | Active execution and required dependencies remain intact while storage growth is controlled. |
| **14 — Developer and operator usability** | OpenAPI documentation, richer examples, convenient CLI/scripts, and a small read-first administrative UI with DAG and attempt-history views. | A user can submit, monitor, diagnose, and retry without direct SQL access. |
| **15 — Shared deployment security** | Authentication, authorization, secret management, restricted network exposure, and audit records. Add tenant isolation only if shared use requires it. | Users cannot access or modify executions outside their permissions. Required before exposing a shared deployment. |
| **16 — Workflow cancellation** | A cancellation API, explicit lifecycle semantics, cooperative worker interruption, and history explaining cancelled or skipped work. | Cancellation races safely with claims, completion, retry, and recovery. |
| **17 — Reusable definitions and versioning** | Separate immutable definition versions, execution snapshots, and handler compatibility/version identity. | Editing a definition cannot alter existing executions or make their handlers ambiguous. |
| **18 — Recurring scheduling** | Schedules referencing definition versions, occurrence deduplication, and explicit timezone, missed-run, and overlap policies. | Scheduler restarts and competing schedulers do not create duplicate occurrences. |
| **19 — Conditional execution** | Conditions based on prior results, explicit skipped-step behavior, and well-defined join/completion rules. | Conditional branches produce deterministic outcomes across retries and restarts. |
| **20 — Compensation workflows** | Explicit compensating actions, their ordering, retries, and history. Treat compensation as business recovery rather than guaranteed rollback. | Partial failure triggers the intended compensations and records compensation failures clearly. |
| **21 — External execution adapters** | External or dynamically resolved handlers, potentially supporting other languages without application registration. Design target identity, security, compatibility, and cancellation first. | External execution preserves the same ownership, recovery, and history guarantees. |

A broker, coordinator partitioning, database sharding, cloud services, and multi-region deployment remain options driven by measured requirements, rather than automatic milestones. Introduce them only when the simpler architecture cannot meet a defined requirement.

## Working Rules

- Preserve the agreed Java/Maven/Spring Boot/PostgreSQL baseline, registered-handler model, and ownership protocol unless an architectural change is separately approved.
- Implement one approved milestone and one logical change at a time. Recording a proposal does not authorize implementation.
- Add forward Flyway migrations when needed; preserve the committed baseline migration.
- Keep documentation current, including API contracts, guarantees, assumptions, and known limitations.
- Run focused checks during development and full Maven verification at milestone boundaries. Run relevant container scenarios before claiming distributed behavior.
- Report changes, actual test results, failures, and remaining risks; recommend a checkpoint commit after a verified milestone.
- Stop for review before beginning the next milestone. Commit or push only with explicit approval.
