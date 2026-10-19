# Distributed Workflow Engine Design

This document explains the implemented design and its limits. [README.md](README.md) contains setup, API examples, the architecture diagram, and reproducible verification commands.

The companion [Detailed Design](DETAILED_DESIGN.md) preserves the handler/API contracts, database schema, lifecycle state machines, coordination protocols, worker behavior, and full decision register from the original design. Read this document for the high-level decisions and the companion for implementation details.

## Architecture Decisions

### Technology and component boundaries

Java 21 and Maven satisfy the assignment constraints. Spring Boot supplies HTTP endpoints, validation, configuration, and process lifecycle support. Spring JDBC keeps claim and fencing SQL explicit, while Flyway manages schema creation. Docker Compose runs the actual application and PostgreSQL locally with reproducible infrastructure.

The API, workflow coordinator, recovery coordinator, persistence layer, and business handlers have separate responsibilities. API and coordinators share one process to simplify deployment; long-running workers execute handlers independently. One artifact supports both runtime profiles, keeping contracts and registered handlers consistent.

### PostgreSQL as the transactional authority

PostgreSQL stores workflow executions, retry cycles, steps, dependency edges, and worker attempts. Relational constraints protect identities and references; JSONB stores variable definitions, inputs, parameters, and results. Each submission atomically stores its definition snapshot and materialized steps/dependencies. Effective retry settings are persisted so deployment changes cannot alter existing runs.

The database also provides durable work discovery. Keeping readiness, ownership, results, and lifecycle transitions in one transactional system avoids a separate database-to-broker delivery protocol. Workers pull eligible steps rather than receiving an in-memory assignment that could disappear during a restart.

### Claims, leases, and fencing

Claims and workflow lifecycle changes use short transactions guarded by the workflow row, with conditional updates and consistent workflow-before-step lock ordering. This coordinates claims with failure, completion, recovery, and manual retry. A claim rechecks workflow status, dependency completion, eligibility time, and remaining allowance, then atomically records ownership and an attempt. Candidate discovery alone grants no ownership.

Each successful claim increments a lifetime attempt token. Renewals and outcome writes require the matching owner/token and an unexpired lease, using current database time after relevant lock waits. Expired leases cannot be revived. Heartbeats update the claimed step independently of the workflow guard; business handlers run outside database transactions.

Defaults are a 30-second lease, 5-second heartbeat, 1-second worker/coordinator polling, and 5-second recovery scanning. Workers reserve capacity before claiming and use separate control executors so slow handlers do not starve heartbeats. A conservative monotonic local deadline triggers best-effort cancellation when ownership cannot be confirmed. Database checks remain authoritative.

### DAG execution, retries, and history

Steps become eligible only after every dependency completes; independent handlers run concurrently. Readiness and retry backoff share an `eligible_at` timestamp. The coordinator marks a workflow `COMPLETED` only after all steps complete.

Retryable failures use capped exponential backoff with multiplier 2. Defaults allow three total attempts per step/cycle, with 1-second initial and 30-second maximum backoff. Permanent failures bypass automatic retries. Abandoned claims also consume allowance, bounding recovery after repeated crashes.

Permanent failure or exhausted allowance atomically fails the step, current cycle, workflow, and waiting steps. Already-running siblings may finish without reopening the workflow. Manual retry requires a failed workflow with no unfinished attempts; it creates a new cycle under the same execution ID, preserves completed outputs, and grants failed steps fresh allowances. Inputs, policies, history, and increasing lifetime tokens are retained.

Current step summaries are separate from durable attempt and cycle history. Paginated read-only APIs expose progress without advancing execution. Registered `StepHandler` implementations receive isolated JSON inputs, parameters, direct dependency outputs, and a stable execution/step idempotency key; orchestration does not depend on business action names.

## Alternatives Considered

These comparisons record design discussions, including the retrospective in [tradeOffs.md](tradeOffs.md). They do not imply that alternatives were prototyped or benchmarked before implementation.

| Alternative | Reason for retaining the current approach |
| --- | --- |
| Managed orchestration such as AWS Step Functions | Credible for business orchestration, but delegates much of the coordination being demonstrated. Our submitted DAG and retry/history contract would require deliberate mapping, and local verification would need a different strategy. |
| A custom engine using DynamoDB and SQS | Conditional writes and transactions could support coordination, but require different relationship/query modeling and reliable state-to-message publication. PostgreSQL fits the related records and varied scheduling/history queries directly. |
| A separate message broker | Could improve work discovery, but adds delivery/acknowledgment behavior and duplicate handling. A durable publication mechanism, such as an outbox, would be needed alongside authoritative claims. |
| Optimistic versions or serializable transactions | Could protect concurrent transitions, but require conflict retries and a workflow-wide protocol. Step-only checks cannot protect claims racing with workflow failure. The explicit workflow guard is easier to inspect. |
| Advisory locks or one authoritative coordinator per workflow | Advisory locks add a cooperating lock convention; a dedicated workflow owner adds routing, failover, and stale-owner protection. Neither is necessary for the initial deployment. |
| Only storing JSON, or adding a reusable definition registry immediately | JSON alone complicates scheduling and concurrent step updates. A registry adds identity/version APIs beyond required submission behavior; snapshots plus relational execution records fit the current scope. |
| Recreating the whole execution on manual retry | Simpler rerun semantics, but repeats successful work. Retry cycles preserve the agreed execution identity, outputs, and history; a new submission remains available for a complete rerun. |

## Tradeoffs

- **Correctness and simplicity over peak throughput:** workflow-row guards make cross-step decisions straightforward, but serialize short state transitions within one workflow. Handlers and separate workflows still run concurrently.
- **Consistent ownership over availability during partitions:** scheduling and durable transitions require PostgreSQL. A disconnected worker cannot safely continue renewing or reporting indefinitely; database loss pauses progress.
- **Fewer infrastructure components at the cost of polling:** one transactional authority simplifies local verification and recovery, while polling adds database traffic and discovery latency.
- **At-least-once execution with bounded retries:** fencing protects engine records, but cannot stop every stale handler or deduplicate external effects. A handler may act externally before its result is lost and later be retried. Handlers must implement business-level idempotency; the stable key alone does not enforce it.
- **Resume successful work rather than refresh it:** manual retry saves work and preserves outputs, assuming those outputs remain valid. Changed inputs or a full refresh require a new execution.
- **Bounded worker capacity and cooperative cancellation:** reserving slots prevents unbounded leased queues. A handler that ignores interruption keeps its slot until it exits; a healthy handler can renew indefinitely because execution timeouts are not implemented.
- **Inspectable history with ongoing storage cost:** attempts and cycles survive retries/restarts, but history and JSON payloads have no application-level retention or size limits. History is not an event log of every intermediate state change.

## Failure Modes

| Scenario | Handling and limitation |
| --- | --- |
| Invalid workflow | Submission rejects invalid structure, cycles, duplicate IDs/dependencies, missing dependencies, unsupported actions, and invalid handler/retry parameters before execution. |
| Worker crash | Recovery rechecks expiry under locks, marks the attempt `ABANDONED`, clears ownership, and schedules backoff if the workflow is active and allowance remains. Exhaustion fails the workflow. |
| Worker loses database connectivity | Unconfirmed renewals do not extend the local ownership window. The worker eventually requests cancellation; database fencing rejects expired/superseded reports. External work may continue despite interruption. |
| Heartbeat races with recovery | Recovery rechecks database time and current ownership after locking. A successfully renewed lease is not abandoned based on stale discovery. |
| Handler failure | Retryable errors receive bounded backoff; permanent errors or exhausted allowance fail the workflow. Waiting steps fail with `WORKFLOW_FAILED`; running siblings settle without automatic retries. |
| Claim/result response lost after commit | The worker reconciles exact attempt identity and durable state. It confirms an uncertain claim before execution and reads a stored terminal outcome before retrying reporting. Reporting failure alone does not rerun the handler. |
| External action succeeds but result is not stored | Recovery may repeat the business action. Downstream idempotency or deduplication is required to prevent duplicate effects. |
| API/coordinator stops | API access, dependency promotion, success finalization, and recovery pause. Durable state remains; workers may continue already-eligible work while ownership is valid. Progress resumes when the process returns. |
| PostgreSQL unavailable | Submissions, claims, renewals, and durable outcomes pause. After restoration, persisted state and lease recovery determine continuation. A named volume survives restarts, but does not protect against disk loss. |
| Retry requested while attempts remain unfinished | The command returns `409`; running attempts must finish or be recovered. Concurrent eligible retry requests cannot create two next cycles. |
| Graceful shutdown | Workers stop claiming, renew/drain for up to 30 seconds, then request cancellation and stop renewal. Unfinished claims recover through expiry. |

With healthy database/coordinator capacity, expiry is ordinarily detected within roughly 35 seconds of the last lease extension, before additional backoff and scheduling delays. This is an operating expectation, not a guaranteed recovery deadline or execution timeout.

Submission and manual retry have no durable HTTP idempotency keys. After an uncertain response, inspect persisted state before repeating a command; repeated submission can create another execution, and a later repeated retry can start another cycle after intervening failure.

## Scalability Considerations

The local stack has one API/coordinator process, two workers with two slots each, and one PostgreSQL instance. It demonstrates concurrency and recovery; no throughput benchmark or production capacity limit has been established.

| Constraint | Path to scale beyond it |
| --- | --- |
| Worker slots | Add workers with `docker-compose up -d --scale worker=3`, then tune per-worker concurrency against database connections and downstream capacity. Handler registrations must remain compatible across API/workers. |
| Polling and coordinator scans | Measure scheduling latency, query load, and fairness; tune indexes, candidate traversal, batching, polling intervals, and connection budgets. Recovery currently discovers up to 100 candidates per pass; this does not guarantee fairness under sustained load. |
| Workflow-row contention | Measure lock waits for large fan-out workflows. Partition coordination or introduce finer-grained protocols only if simpler tuning cannot meet a defined target. More workers alone cannot remove this contention. |
| API/coordinator capacity and availability | Add API instances and verify concurrent coordinator operation; separate roles when independent scaling or isolation is useful. State transitions are designed for concurrency, but a replicated coordinator deployment remains unverified. |
| Database availability and storage growth | Add tested backups/restore and suitable database high availability; bound payloads and archive terminal history. Consider external storage for large results. |
| Work discovery at higher load | Consider notifications or a broker after measurement, preserving authoritative database claims, fencing, and durable publication. Changing delivery alone does not remove database bottlenecks. |

## Future Improvements

Priorities follow [Milestones.md](Milestones.md), with each extension requiring its own design and verification:

1. **Safe repeated commands:** durable HTTP idempotency keys for submission/retry, including payload mismatch detection and lost-response reconciliation.
2. **Execution deadlines:** optional per-step timeouts, explicit outcome classification, and safe late-result handling, separate from lease ownership.
3. **Operational reliability:** structured diagnostics, metrics/alerts, readiness checks, CI verification, and tested backup/restore procedures.
4. **Measured scaling and storage controls:** establish load baselines, improve scans/fairness and connection budgets, verify multiple API/coordinators, and add payload limits and history retention.
5. **Developer/operator usability:** OpenAPI documentation, a convenient CLI, richer examples, and a small inspection UI.
6. **Shared deployment security:** authentication, authorization, secrets, restricted exposure, and audit records. This is a prerequisite for any shared/public deployment, regardless of roadmap order.
7. **Additional workflow capabilities:** cancellation, reusable definitions/versioning, recurring scheduling, conditional steps, compensation, and external execution adapters, in that order after the reliability work.
