# Distributed Workflow Engine Design

This document explains the implemented design and its limits. [README.md](README.md) contains setup, API examples, the architecture diagram, and reproducible verification commands.

The companion [Detailed Design](DETAILED_DESIGN.md) preserves the handler/API contracts, database schema, lifecycle state machines, coordination protocols, worker behavior, and full decision register from the original design. Read this document for the high-level decisions and the companion for implementation details.

## Assumptions and Scope

The initial design relies on the following workload, handler, and deployment assumptions. They explain the boundaries within which the coordination guarantees apply.

| Assumption | Description |
| --- | --- |
| Workflows are static DAGs | Steps and dependencies are fixed at submission. Loops, dynamically generated steps, and recurring schedules are outside the initial scope. |
| Executions retain their submitted configuration | Definitions, inputs, parameters, and effective retry policies remain unchanged during execution and manual retry. Changing them requires a new submission. |
| Handlers are trusted application code | Handlers are implemented and registered before deployment. The engine does not sandbox uploaded code or execute arbitrary user commands. |
| Handler deployments remain compatible | API and workers share compatible handler registrations and contracts. Definition snapshots preserve configuration, but do not freeze deployed handler code. |
| Handlers support concurrent invocation | Multiple workers or threads may invoke the same handler implementation. Handlers must be stateless or thread-safe. |
| Repeated business execution is acceptable or deduplicated | Failures can cause a step to execute again. Handlers with external effects must provide idempotency or downstream deduplication; fencing protects engine state only. |
| Completed outputs remain valid for manual retry | Manual retry reuses successful results and assumes their associated external effects remain valid. Fresh computation requires a new execution. |
| PostgreSQL remains the single authority | Coordination relies on one writable PostgreSQL database and its transaction guarantees. The initial deployment does not provide database replication or automatic failover. |
| Recovery requires restored infrastructure | Eventual progress assumes database connectivity, coordinator availability, worker capacity, and remaining retry allowance. Persistent outages can pause execution indefinitely. |
| Database time is suitable for lease decisions | Lease expiry uses database time rather than agreement between worker clocks. Large database clock jumps can affect lease timing; workers use monotonic time for their local safety window. |
| Handlers cooperate with cancellation and manage external calls | Interruption is best effort. Handlers should check cancellation and use appropriate external-call timeouts; leases do not impose a maximum execution duration. |
| Initial workloads fit a local evaluation deployment | DAGs, JSON payloads, and retained history are expected to fit available database and process resources. No production throughput, strict scheduling latency, or fairness guarantee has been established. |
| Deployment is a trusted local environment | Authentication, authorization, tenant isolation, and production secrets management are deferred. Published ports bind to loopback for local evaluation. |

## Architecture Decisions

### Technology and component boundaries

Java 21 and Maven satisfy the assignment constraints. Spring Boot supplies HTTP endpoints, validation, configuration, and process lifecycle support. Spring JDBC keeps claim and fencing SQL explicit, while Flyway manages schema creation. Docker Compose runs the actual application and PostgreSQL locally with reproducible infrastructure.

The API, workflow coordinator, recovery coordinator, persistence layer, and business handlers have separate responsibilities. API and coordinators share one process to simplify deployment; long-running workers execute handlers independently. One artifact supports both runtime profiles, keeping contracts and registered handlers consistent.

### PostgreSQL as the transactional authority

PostgreSQL stores workflow executions, retry cycles, steps, dependency edges, and worker attempts. Relational constraints protect identities and references; JSONB stores variable definitions, inputs, parameters, and results. Each submission atomically stores its definition snapshot and materialized steps/dependencies. Effective retry settings are persisted so deployment changes cannot alter existing runs.

The database also provides durable work discovery. Keeping readiness, ownership, results, and lifecycle transitions in one transactional system avoids a separate database-to-broker delivery protocol. Workers pull eligible steps rather than receiving an in-memory assignment that could disappear during a restart.

This makes database reachability an availability dependency. The [network partitions and CAP tradeoff](#network-partitions-and-cap) explains which operations pause when components cannot reach that authority.

### Claims, leases, and fencing

Claims and workflow lifecycle changes use short transactions guarded by the workflow row, with conditional updates and consistent workflow-before-step lock ordering. This coordinates claims with failure, completion, recovery, and manual retry. A claim rechecks workflow status, dependency completion, eligibility time, and remaining allowance, then atomically records ownership and an attempt. Candidate discovery alone grants no ownership.

Each successful claim increments a lifetime attempt token. Renewals and outcome writes require the matching owner/token and an unexpired lease, using current database time after relevant lock waits. Expired leases cannot be revived. Heartbeats update the claimed step independently of the workflow guard; business handlers run outside database transactions.

Defaults are a 30-second lease, 5-second heartbeat, 1-second worker/coordinator polling, and 5-second recovery scanning. Workers reserve capacity before claiming and use separate control executors so slow handlers do not starve heartbeats. A conservative monotonic local deadline triggers best-effort cancellation when ownership cannot be confirmed. Database checks remain authoritative.

### DAG execution, retries, and history

Steps become eligible only after every dependency completes; independent handlers run concurrently. Readiness and retry backoff share an `eligible_at` timestamp. The coordinator marks a workflow `COMPLETED` only after all steps complete.

Retryable failures use capped exponential backoff with multiplier 2. Defaults allow three total attempts per step/cycle, with 1-second initial and 30-second maximum backoff. Permanent failures bypass automatic retries. Abandoned claims also consume allowance, bounding recovery after repeated crashes.

Permanent failure or exhausted allowance atomically fails the step, current cycle, workflow, and waiting steps. Already-running siblings may finish without reopening the workflow. Manual retry requires a failed workflow with no unfinished attempts; it creates a new cycle under the same execution ID, preserves completed outputs, and grants failed steps fresh allowances. Inputs, policies, history, and increasing lifetime tokens are retained.

Current step summaries are separate from durable attempt and cycle history. Paginated read-only APIs expose progress without advancing execution. Registered `StepHandler` implementations receive isolated JSON inputs, parameters, direct dependency outputs, and a stable execution/step idempotency key; orchestration does not depend on business action names.

The [provider records validation demo](PROVIDER_RECORDS_VALIDATION_DEMO.md) exercises that extension contract with five business handlers and an external HTTP client. Handlers return report/cleaned-data JSON through ordinary persisted step outputs; the client handles presentation and local file export. Data-quality findings are successful business results, while malformed input is a permanent error and explicitly simulated transient faults use the existing retry mechanism. No domain-specific routes, runtime logic, or schema changes are required.

### Operational observability

An optional Compose overlay adds Prometheus and a provisioned Grafana dashboard using the same application image. Micrometer records process capacity, operations, and lifecycle transitions; structured logs carry execution identities without adding them as metric labels. Newly applied lifecycle events are emitted after transaction commit, so rollback and duplicate reports do not inflate completion counts. Telemetry remains best effort and never replaces durable history.

Only monitoring-mode API processes collect aggregate database observations. A separate single-thread sampler and one-connection read-only pool cache consistent snapshots of eligible work, pending promotion, and expired leases. Scrapes read memory. Bounded queries use database time so eligibility and expiry can change without a write. Failures retain the last snapshot with explicit freshness/success signals; stale state is hidden from queue/lease panels and their alerts. Sampling cost grows with active work and requires measurement at higher load.

Monitoring-mode workers expose only an internal management listener; default workers remain non-web. The overlay, discovery, and fault tooling are Compose-specific; metric definitions and dashboard intent are reusable elsewhere. [OBSERVABILITY.md](OBSERVABILITY.md) records operating thresholds, failure diagnosis, verification, and limits.

See the [detailed observability tradeoffs](tradeOffs.md#3-observability-and-failure-diagnosis-milestone-10a) for alternatives, accounting guarantees, and the distinction between health and progress.

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

### Correctness and simplicity over peak throughput

Workflow-row guards give claims, failure, recovery, and manual retry one coordination point, making cross-step rules easier to verify. The cost is that short state-changing transactions within the same workflow serialize, even when they concern different steps. Business handlers run outside those transactions, so independent steps and separate workflows still execute concurrently. Adding workers alone cannot remove contention on a busy workflow; finer-grained coordination would need evidence that its extra complexity is worthwhile.

### Network partitions and CAP

During a partition, we prioritize consistent ownership and durable state over availability of database-dependent operations. Disconnected workers cannot independently authorize claims or extend ownership without confirmed renewal; their local deadline triggers best-effort cancellation. Database-connected components may continue, while complete database isolation pauses durable coordination. Returning `503` does not satisfy CAP availability. [AWS CAP discussion](https://docs.aws.amazon.com/whitepapers/latest/availability-and-beyond-improving-resilience/cap-theorem.html)

This is an application-level policy around one PostgreSQL authority, with no database replicas, quorum protocol, or automatic failover. CAP consistency means single-copy, linearizable behavior, not merely valid records; protecting our execution invariants does not establish a blanket CP classification or a linearizable contract for every API. See [Failure Modes](#failure-modes) for recovery behavior and the execution-semantics tradeoff below for external-effect limitations.

### Fewer infrastructure components at the cost of polling

Using PostgreSQL for both execution state and work discovery avoids operating a separate broker and coordinating database writes with message publication. Pending work and retry schedules remain discoverable after application restarts. The cost is repeated polling queries and a delay between work becoming eligible and a worker finding it. Shorter intervals reduce that delay but increase database load; a broker or notification mechanism should follow measured need and preserve authoritative claim checks.

### At-least-once execution with bounded retries

Retrying expired or unsuccessful attempts allows progress after worker and connectivity failures, but can repeat business work. A handler may act externally before its result is lost, and fencing cannot undo that effect or stop every stale handler. Handlers must implement business-level idempotency where the effect occurs; the stable execution/step key alone does not enforce it. Retry allowances bound repeated execution, but counting abandoned claims means an allowance can be exhausted without a confirmed business failure.

### Reusing completed outputs versus refreshing work

Manual retry preserves completed outputs and reruns failed steps in a new cycle, avoiding repeated upstream work and retaining the original execution history. Inputs and effective policies remain unchanged, so retry resumes the same submitted configuration. The cost is assuming that saved outputs and any associated external state remain valid; the engine does not check freshness or repeat successful business effects. Changed inputs, refreshed outputs, or a complete rerun require a new execution.

### Bounded worker capacity and cooperative cancellation

Workers reserve execution slots before claiming, so leases are not consumed by work waiting in an unbounded local queue. Excess work remains in PostgreSQL for another available worker or a later poll, while separate control executors keep heartbeats independent of handler threads. The cost is that a handler ignoring interruption retains its slot until it exits; cancellation does not guarantee immediate capacity recovery. A healthy handler can renew indefinitely because ownership leases are not execution timeouts, and execution deadlines are not implemented.

### Inspectable history with ongoing storage cost

Keeping attempts and cycles separate from current step state preserves failures, abandonment, results, and manual-retry history across restarts. Paginated APIs make that history inspectable without returning it all in each status response. The cost is ongoing storage and index maintenance; pagination bounds collection length, but history retention and individual JSON payload sizes have no application-level limits. This history records attempts and cycles, not an audit event for every intermediate state change. Archival or external result storage would need rules that preserve data still required by active executions and retries.

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
3. **Operational reliability:** milestone 10a adds local diagnostics, metrics/alerts, health probes, and a failure walkthrough. Broader CI verification, backup/restore procedures, centralized logs/tracing, and production alert delivery remain follow-ups.
4. **Measured scaling and storage controls:** establish load baselines, improve scans/fairness and connection budgets, verify multiple API/coordinators, and add payload limits and history retention.
5. **Developer/operator usability:** OpenAPI documentation, a convenient CLI, richer examples, and a small inspection UI.
6. **Shared deployment security:** authentication, authorization, secrets, restricted exposure, and audit records. This is a prerequisite for any shared/public deployment, regardless of roadmap order.
7. **Additional workflow capabilities:** cancellation, reusable definitions/versioning, recurring scheduling, conditional steps, compensation, and external execution adapters, in that order after the reliability work.
