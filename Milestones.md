# Roadmap from Milestone 3

## Starting point and priorities

Milestone 2 is committed as `91600a6`. It provides submission/status APIs, durable storage, registered handlers, claims, fencing, heartbeats, basic retry transitions, and sequential execution. Verification at that checkpoint passed 24 unit tests and 21 integration tests.

This roadmap follows the [original assignment](<Take Home Distributed Systems Exercise - athenahealth.pdf>) and its instruction to prioritize fundamentals before stretch goals. [PROJECT_DESCRIPTION.md](PROJECT_DESCRIPTION.md) records the project scope, and the approved contracts in [DESIGN.md](DESIGN.md) remain authoritative. The larger remaining milestones in [Plan.md](Plan.md) are divided here into **five smaller milestones, 3–7**, followed by optional improvements. This document is the agreed milestone breakdown for future work; Plan.md preserves the earlier plan and environment-validation history.

Priority order:

1. Complete and demonstrate assignment requirements.
2. Strengthen reliability and operational safety.
3. Improve measured scalability.
4. Improve usability and add workflow capabilities.

## Milestones 3–7: complete the assignment

### Milestone 3 — Parallel DAG execution and competing workers

**Implementation checkpoint:** Authorized, implemented, verified, and committed as `1136fc8`. Focused verification passed all nine new integration scenarios; full clean verification passed 24 unit tests and 30 integration tests without failures or skips. Image build and two-worker Compose startup passed. HTTP checks of sequential, parallel, and four-branch workflows confirmed overlap, join ordering, exclusive attempts, and both workers' participation within their capacity limits. The deployment and example extend the existing engine without production Java behavior, API, schema, or dependency changes. Subsequent milestone 4 work is recorded below.

**Outcome:** Demonstrate correct execution across multiple workers.

- Make two worker containers the default local deployment, each with bounded concurrency.
- Add a parallel sample with branches and a join.
- Verify and fix the existing eligibility/claim paths as necessary; retain PostgreSQL coordination and workflow-before-step lock ordering.
- Test workflow failure racing with claims and independent step completion.

**Acceptance:** Independent branches overlap; joins wait for every dependency; competing workers create only one valid claim per step. No new claim succeeds after workflow failure commits, and late success cannot reopen a failed workflow.

**Reason for this boundary:** Establish normal distributed execution before introducing recovery from interrupted execution.

### Milestone 4 — Crash recovery and connectivity failures

**Implementation checkpoint:** Authorized, implemented, verified, and committed as `457e0ec`. Clean verification passed 29 unit tests and 47 PostgreSQL integration tests without failures or skips. `RecoveryCoordinator` and guarded recovery settle expired attempts, while explicit terminal-outcome lookup reconciles lost result responses. New tests cover ownership deadlines, recovery/renewal races, uncertainty, and shutdown. The portable container script built the image and verified process death, worker network isolation, and a full database pause; all recovered with one abandoned and one completed attempt plus downstream completion. No public API or schema changes were needed. Subsequent milestone 5 work is recorded below.

**Outcome:** Interrupted work recovers automatically without accepting stale results.

- Add periodic expired-lease recovery, including running attempts on failed workflows.
- Under the existing locking protocol, recheck expiry after acquiring locks and use current database time.
- Atomically mark expired attempts `ABANDONED`, clear ownership, and schedule backoff or permanent failure according to the persisted allowance. Abandoned claims consume attempts.
- Verify local ownership deadlines, transient renewal failures, explicit rejection, and uncertain claim/outcome responses.
- Verify graceful draining and handlers that ignore interruption retain their capacity until they exit.

**Acceptance:** Killing a worker permits replacement execution after expiry and backoff. A concurrent renewal prevents mistaken abandonment. Stale results cannot change newer state. Database connectivity loss stops unsafe reporting, and restoration permits recovery. Concurrent recovery passes are harmless.

Use controlled PostgreSQL race tests and container-level crash/partition tests; basic mocks alone are insufficient evidence.

### Milestone 5 — Complete retries and manual workflow retry

**Implementation checkpoint:** Authorized and implemented; changes remain uncommitted for review. Clean verification passed 42 unit tests and 70 PostgreSQL integration tests with no failures or skips. The registered `FAIL_UNTIL_ATTEMPT` handler and retry endpoint demonstrate automatic success, permanent failure, and new-cycle manual resumption. Tests cover backoff, allowances, preserved outputs/history, concurrency, validation, and rollback. The image built and portable container checks passed, including actual API/worker restart during backoff. No new schema or dependencies were needed. Milestone 6 has not started.

**Outcome:** Users can recover failed workflows while preserving completed work and history.

- Add a predefined demonstration handler that fails a configurable number of lifetime attempts.
- Complete automatic retry verification: capped exponential backoff, total-attempt limits, permanent failures, unexpected exceptions, and restart persistence.
- Implement `POST /api/v1/executions/{id}/retry`.
- Retry only failed executions with no unfinished attempts. Preserve completed steps and outputs; reset failed steps in a new cycle with fresh allowances.
- Preserve previous cycles, attempts, inputs, definition, and increasing fencing counters.

**Acceptance:** A workflow can fail, be manually retried, and complete without rerunning successful predecessors. Concurrent retry requests create only one new cycle. Active attempts block retry with the agreed `409` response.

### Milestone 6 — Listing and execution history APIs

**Outcome:** Users can inspect executions and understand failures through the API.

Implement the remaining agreed read routes:

- `GET /api/v1/executions`
- `GET /api/v1/executions/{id}/cycles`
- `GET /api/v1/executions/{id}/attempts`

Use the existing design's response fields, filters, keyset ordering, and resource/filter-bound cursors. Default page size remains 50, maximum 200.

**Acceptance:** Pagination handles timestamp ties and multiple cycles without ordering errors. Invalid filters/cursors return useful errors. History distinguishes reported failures, abandonment, and steps blocked by workflow failure.

**Why this is required:** Listing is an explicit assignment requirement. Cycle/attempt APIs also complete our approved visibility contract.

### Milestone 7 — Reproducible delivery and final verification

**Outcome:** The repository is ready for assignment submission.

- Provide runnable sequential, parallel, retry, and permanent-failure examples.
- Add repeatable demonstrations for worker competition, crash recovery, connectivity failure, manual retry, and restart persistence.
- Verify clean Maven builds, Docker image builds, and the exact `docker-compose up` startup path from a fresh checkout.
- Verify application and database restarts against retained storage.
- Complete README architecture diagram, API examples, troubleshooting, testing commands, and configuration guidance.
- Update DESIGN with actual guarantees, consistency/availability trade-offs during partitions, limitations, and prioritized future work. Remove stale implementation-status statements.

**Acceptance:** A reviewer can reproduce every required capability using documented prerequisites and commands.

**This is the assignment-complete boundary. Optional work must not delay it.**

## Optional milestones after the assignment

These are proposed extensions, not requirements for submission. Each receives its own focused design before implementation, particularly where states or public contracts change.

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

A broker, coordinator partitioning, database sharding, cloud services, and multi-region deployment remain **measurement-driven architectural options**, rather than automatic milestones. Introduce them only when the simpler architecture cannot meet a defined requirement.

## Working rules and defaults

- Retain Java 21, Maven, Spring Boot, PostgreSQL, registered Java handlers, and the current ownership protocol throughout milestones 3–7.
- Add forward Flyway migrations when needed; preserve the committed baseline migration.
- Keep documentation current within every milestone.
- Run focused checks during development and full Maven verification at each milestone boundary; run relevant container scenarios before claiming distributed behavior.
- Report results and limitations, then stop for review. Commit only with approval and do not automatically start the next milestone.
- Recording this roadmap does not authorize implementation. Work proceeds one approved milestone at a time.
