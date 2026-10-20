# Technology and Operational Trade-offs

## Status and purpose

This document records our architecture and operational discussions. Sections 1–2 are retrospective comparisons of managed AWS orchestration versus a custom engine, and relational versus NoSQL storage; they do not imply that every alternative was evaluated before coding. Section 3 records the alternatives discussed and decisions agreed before implementing milestone 10a, together with findings from its verification.

The implemented architecture remains the Java/Spring Boot engine with PostgreSQL described in [DESIGN.md](DESIGN.md). Milestone 10a was separately authorized and implemented as an optional monitoring deployment. This document records the reasoning; it does not authorize additional optional work.

The decision criteria are assignment coverage, correctness, inspectability, reproducible local verification, and manageable implementation complexity. Production suitability also depends on workload, availability requirements, operational capability, and measured performance.

## 1. Managed orchestration versus building the engine

### Different services solve different problems

| Technology | Responsibility |
| --- | --- |
| AWS Step Functions | Managed workflow orchestration: transitions, parallel execution, retries, and history. |
| Amazon SQS | Delivery of queued work to consumers; it does not evaluate DAG dependencies. |
| Amazon SNS | Event publication to independent subscribers; it does not provide workflow scheduling. |
| PostgreSQL or DynamoDB | Durable state, with database operations that a custom engine can use for coordination. |

The central distinction is who implements orchestration. Step Functions supplies an engine. DynamoDB and SQS supply building blocks for an engine we implement ourselves. AWS deployment and PostgreSQL storage are compatible choices.

### Option A: Step Functions owns orchestration

```text
Client -> Java REST API -> Step Functions Standard
                              <-> Java activity workers
```

The Java application would validate submitted workflows, translate them into Amazon States Language definitions, start executions, and adapt status/history/recovery operations to our API. Java handlers would still perform business work. Step Functions would own workflow state and execution coordination, replacing most of our custom claim, scheduling, and recovery logic.

Java activity workers can poll using `GetActivityTask`, receive task tokens, and report heartbeats, success, or failure. [AWS Activities documentation](https://docs.aws.amazon.com/step-functions/latest/dg/concepts-activities.html)

The assignment's illustrative `A -> Parallel(B, C) -> D -> E` workflow maps naturally to Step Functions. A fully generic submitted DAG requires a deliberate translation strategy: nested Parallel branches are self-contained and are not automatically equivalent to every dependency graph we accept. This is a mapping problem to evaluate, not a claim that Step Functions cannot support the workload. [Parallel state documentation](https://docs.aws.amazon.com/step-functions/latest/dg/state-parallel.html)

**Benefits:** managed orchestration, durable execution tracking, built-in retry/backoff, and less custom engine code. Standard workflows are the relevant starting point for durable orchestration with activity workers. Eligible failed Standard executions also support redrive, which preserves successful work and history; its detailed rules would need comparison with our manual-retry cycle contract. [Workflow types](https://docs.aws.amazon.com/step-functions/latest/dg/choosing-workflow-type.html), [redrive behavior](https://docs.aws.amazon.com/step-functions/latest/dg/redrive-executions.html)

**Assignment trade-offs:** much of the coordination behavior being evaluated would be supplied by AWS. We would demonstrate service integration and definition translation rather than implement those mechanisms directly. Our generic DAG format, statuses, retry rules, and history views would need adaptation. Local verification also requires care: AWS currently labels Step Functions Local unsupported and states that it lacks feature parity, so emulator tests alone would not establish behavior of the managed service. [Local testing documentation](https://docs.aws.amazon.com/step-functions/latest/dg/sfn-local.html)

AWS describes Standard workflows as having an exactly-once execution model, subject to configured retries. This must not be generalized into exactly-once effects in arbitrary external business systems. Retried operations and uncertain external outcomes still require idempotency design. [Execution guarantees](https://docs.aws.amazon.com/step-functions/latest/dg/choosing-workflow-type.html)

**Assessment:** a credible alternative, especially for production business orchestration where implementing an engine is not the product goal. We should not claim that the assignment forbids it or that a custom engine is universally superior.

### Option B: Custom Java engine with DynamoDB and SQS

```text
Java API/coordinator -> DynamoDB execution state
                            -> durable dispatch -> SQS -> Java workers
Java workers -> conditional ownership and outcome updates in DynamoDB
```

We would still implement dependency evaluation, claims, fencing, retry limits/backoff, recovery, history, and completion/failure decisions. DynamoDB conditional writes and transactions can protect state transitions; SQS provides work delivery. This is conceptually similar to our engine with different persistence and dispatch mechanisms. [DynamoDB transactions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html)

Two additional protocols need explicit design:

- **Duplicate delivery:** Standard SQS may deliver duplicates, including during visibility timeout. A received message is not sufficient authorization to execute a step; the worker must acquire valid ownership against authoritative state. [SQS visibility documentation](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html)
- **State-to-message consistency:** recording that a step is ready and publishing its work message are separate operations. An outbox or change-data-capture approach can recover from failures between them; consumers must tolerate duplicates. [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)

SNS could publish completed/failed notifications to separate subscribers. Competing workers would normally share an SQS queue. Fanout to separate subscriber queues intentionally creates multiple deliveries and is useful for independent reactions, not exclusive assignment of one business step. [SNS/SQS fanout](https://docs.aws.amazon.com/sns/latest/dg/sns-sqs-as-subscriber.html)

**Assessment:** reasonable for an AWS-centered deployment with suitable access patterns and workload requirements. It adds storage modeling and dispatch-consistency responsibilities to the assignment implementation.

### Option C: Deploy our existing engine on AWS

```text
Java API/coordinators and workers on ECS <-> RDS PostgreSQL
```

This preserves our execution model while changing deployment infrastructure. SQS could later assist work discovery, with PostgreSQL retaining authority over claims and outcomes and an outbox providing reliable publication. Such a change might reduce polling pressure, but would not automatically eliminate database contention or workflow-level serialization. It should follow measurement and deployment requirements.

### Why our implemented approach fits the assignment

- **Visible coordination:** claims, leases, fencing, recovery, and failure races are implemented in our code and exercised by controlled tests.
- **One transactional authority:** execution state and work eligibility share PostgreSQL, avoiding a separate database-to-broker publication protocol initially.
- **Reproducible verification:** Docker Compose runs the actual database and application processes, including real crash/partition and restart scenarios.
- **Direct semantics:** the engine implements our generic DAG, manual-retry cycles, and history contract directly.
- **Bounded scope:** fewer infrastructure components leave more implementation and verification effort for correctness.

The accepted costs are polling, contention on workflow guards, a shared database dependency, and responsibility for operating a custom engine. We have not demonstrated production-scale throughput or database high availability.

## 2. Relational versus NoSQL storage

NoSQL covers several database models. Our concrete comparison is PostgreSQL versus DynamoDB; DynamoDB-specific observations should not be generalized to every NoSQL database.

### Why PostgreSQL fits our execution model

**Related records:** workflows contain cycles, steps, dependencies, and attempts. Foreign keys protect references, and unique constraints/indexes protect identities and the one-running-attempt rule. Application logic still validates DAGs and enforces ownership; constraints alone do not implement the engine. [PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)

**Atomic lifecycle changes:** a permanent failure records the attempt outcome, fails the step/current cycle/workflow, and fails waiting steps together. Manual retry similarly creates a cycle and resets eligible state atomically. Transactions and our workflow-row guard coordinate these changes with competing claims. Choosing SQL alone would not guarantee correctness without that protocol. [PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html)

**Multiple query patterns:** scheduling, dependency checks, lease recovery, execution listing, and history require different views of related records. SQL joins, filters, and indexes make these queries straightforward to express and inspect.

**Flexible business payloads:** the execution relationships have a stable structure while definitions, inputs, parameters, and results vary. PostgreSQL JSONB accommodates those payloads without adding columns for each handler. [PostgreSQL JSON support](https://www.postgresql.org/docs/current/datatype-json.html)

### How DynamoDB would differ

DynamoDB can represent related records using composite keys, item collections, and adjacency lists. Conditional writes and multi-item transactions can implement strong coordination guarantees. The rationale for PostgreSQL is not that NoSQL lacks transactions or cannot model relationships. [Relationship modeling](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-adjacency-graphs.html), [transaction semantics](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html)

The design would start with access patterns and choose keys/indexes and any denormalized representations to serve them. New query requirements may require additional indexes or stored representations. Integrity between related items would primarily be enforced by application write protocols rather than relational foreign keys. [DynamoDB modeling guidance](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-modeling-nosql.html)

Consistency also depends on the read path. DynamoDB supports strongly consistent reads on tables and local secondary indexes, while global secondary indexes provide eventual consistency. A discovery index may return an outdated candidate; conditional ownership checks must still validate execution eligibility before business work starts. This is manageable, but must be designed explicitly. [DynamoDB read consistency](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html)

| Consideration | PostgreSQL in this project | DynamoDB alternative |
| --- | --- | --- |
| Coordinated state changes | Transactions, conditional updates, and workflow-row guards | Conditional writes and explicitly modeled transactions |
| Relationship integrity | Database constraints plus application validation | Application write protocols and item/key design |
| Scheduling/history queries | Joins and indexed SQL queries | Access-pattern-specific keys, indexes, and possible denormalization |
| Flexible payloads | JSONB alongside relational records | Document attributes alongside keys |
| Local verification | Actual PostgreSQL engine in Compose | Local tooling plus verification of relevant managed-service behavior |
| Scaling responsibilities | Query/index tuning, lock contention, connections, database deployment | Partition/index design, hot keys, and coordination contention |

**Assessment:** DynamoDB deserves evaluation for an AWS-centered system with well-defined access patterns and a demonstrated need to distribute substantial traffic across independent executions. Moving databases would not automatically remove contention within one workflow or make the engine correct. PostgreSQL also has scaling options; neither performance nor cost superiority has been measured here.

## 3. Observability and failure diagnosis (milestone 10a)

### Why this stretch goal

The existing engine already demonstrated claims, leases, fencing, retries, durable history, and crash recovery. Its per-execution APIs and tests explained individual outcomes, but operators had limited visibility into aggregate backlog, capacity pressure, stalled coordination, and recovery delays. Health alone could not answer whether workflows were advancing.

We chose observability as a bounded extension of that implementation: make failure behavior visible, explain the signals, and demonstrate diagnosis through controlled faults. The senior/principal-level design value is in connecting symptoms to ownership and recovery semantics, defining what observations can establish, and protecting execution from monitoring failures. A visual dashboard makes that reasoning reviewable. Additional execution features and broader operational hardening remain separate milestones.

### Decision: process metrics plus sampled durable state

We retained the original hybrid proposal after discussing alternatives:

| Question | Source | Reason |
| --- | --- | --- |
| Are workers occupied? How long do handlers and database operations take? | In-memory gauges, counters, and timers in each process | Observe local activity directly without querying durable storage. |
| What runnable work is waiting, and for how long? | Periodic aggregate SQL using database time | Process-local counters cannot reliably reconstruct current durable state across crashes and restarts. |
| Is dependency-ready work awaiting promotion? Are expired claims awaiting recovery? | The same database sampler | Detect incomplete orchestration and recovery, including unfinished claims on failed workflows. |
| What happened to a particular execution or attempt? | Durable API/history plus structured lifecycle logs | Preserve detailed diagnosis without creating a metric series for every execution. |

The collection path is:

```text
API sampler -> aggregate SQL -> cached snapshot -> metrics endpoint -> Prometheus -> Grafana/alerts
Workers     -> local metrics -----------------> metrics endpoints -> Prometheus
```

Only the monitoring-enabled API samples durable state. Its own thread runs two read-only aggregate SELECTs in a repeatable-read transaction. The default fixed delay is five seconds **after a collection finishes**, so collection does not overlap or catch up in bursts. Scraping reads the cached snapshot in memory; another scraper or dashboard refresh does not launch another database query.

Queries select relevant active workflow state and expired running claims rather than calculating totals over historical executions. This limits the requested data, but does not guarantee a cheap physical plan: joins, indexes, statistics, and active-work volume still determine cost. We measure collection duration/errors so this decision can be revisited with evidence.

A dedicated pool capped at one connection limits monitoring concurrency and avoids consuming the application's connection slots. Connection acquisition defaults to 500ms; statement and lock waits also default to 500ms each. JDBC connection/network budgets come from the database URL. These are limits on individual stages, not a guaranteed total collection deadline. The sampler still shares PostgreSQL CPU, I/O, and storage with execution; a separate pool does not isolate those resources.

The accepted cost is a small recurring read workload and delayed visibility. Sampling, scraping, rule evaluation, and alert hold periods all contribute to detection time. Five seconds is a configurable local demonstration choice, not a production freshness guarantee.

### The summary-table alternative

We considered updating a dedicated metrics table whenever workers or coordinators change durable state. This can replace repeated aggregation over workflow records with cheaper reads of precomputed counts. If that table lives in the same PostgreSQL database, it reduces read computation while adding writes; it does not free the workflow database from monitoring load.

Updating state and summaries in one transaction provides atomic accounting, but introduces several obligations:

- Every relevant transition must update the summary correctly: claims, outcomes, retry scheduling, recovery, permanent failure, dependency promotion, and manual retry. Duplicate/rejected reports and rolled-back transactions must not change counts.
- A shared counter row can become a lock hotspot across otherwise independent workflows. Buckets or sharded counters reduce contention but complicate reads and maintenance.
- Summary-update failures can fail execution transactions. Updating summaries separately instead introduces a gap where state commits but the summary does not; repair or a durable delivery mechanism becomes necessary.
- Summaries need a rebuild/reconciliation strategy as definitions evolve or bugs are corrected.

Time-derived state adds another difficulty. A retry becomes eligible when its backoff ends, and a lease becomes expired when time passes, even if no transaction updates either record. The oldest waiting item can also change when the previous oldest leaves. Simple increment/decrement counters do not maintain these answers by themselves; timers, deadline-aware projections, or periodic reconciliation are still needed.

**Decision:** keep the sampled queries for this scope. Introduce a summary for a specific expensive metric if measurements show that its read savings justify the extra write protocol, contention, and repair paths.

### Other collection approaches considered

| Approach | Benefit | Cost or limitation for this engine |
| --- | --- | --- |
| Process-local metrics only | Lowest collection complexity; no observation queries | Good for activity and latency; incomplete for durable backlog after restarts, missed events, or time-based eligibility changes. |
| Aggregate SQL during each scrape | Straightforward implementation | Database work depends on scraper frequency/concurrency; slow queries delay the metrics response. The chosen cache separates these concerns. |
| Transactional outbox plus asynchronous projection | Durable transition delivery can feed an independently queryable read model | Adds an outbox write, a consumer, deduplication/checkpoints, replay/rebuild, and projection lag. Time-derived state still needs handling. |
| Change-data capture | Derive a read model from database changes with fewer application instrumentation paths | Adds capture infrastructure and schema/event interpretation; the projection must recover, reconcile, and handle time-derived state. |
| Read replica | Move observation reads off the primary | Requires replica operations and accepts replication lag; aggregation cost still exists on the replica. |
| Materialized summary refreshed periodically | Reuse one computed result across readers | Refresh work and staleness remain; this does not by itself define how every transition or deadline incrementally updates the summary. |
| Database notifications | Signal that cached state may need refreshing | Useful as refresh hints; notifications alone do not provide a durable, replayable projection or signal deadlines passing without writes. A fallback refresh/reconciliation path remains necessary. |

These alternatives were discussed, not implemented or benchmarked. A growing active workload, measured query pressure, stricter freshness requirements, or multiple consumers would justify revisiting them. No additional broker, projection service, or storage system is required for the local demonstration.

### Freshness and accounting are explicit contracts

On collection failure, we retain the last snapshot and last-success timestamp while exporting failure status. Before the first successful sample, state gauges are NaN. Queue/lease panels and related alerts require both a successful latest collection and a sample younger than 15 seconds. Missing or stale observations therefore do not masquerade as zero backlog. A separate observation-unavailable alert explains the loss of visibility.

Lifecycle observations are emitted after transaction commit, only when a transition was newly applied. Rollbacks and duplicate or stale result reports do not count another completion. A process can still die after committing state and before emitting telemetry, so counters and logs are best-effort operational evidence. Durable history remains the authority. Handler duration also differs from committed completion: a handler may finish while reporting its result fails.

Metric labels use bounded event, operation, outcome, and loop values. Execution, step, attempt, and worker identities belong in diagnostic logs rather than custom metric labels. Prometheus still identifies individual scrape targets, allowing worker capacity and renewal behavior to be inspected separately. Routine successful heartbeats are measured without logging each one; lifecycle logs exclude submitted parameters and result payloads.

### Optional deployment and portability

Instrumentation is part of the application artifact. The optional Compose overlay enables its monitoring profile and database sampler, adds Prometheus/Grafana, and provisions scrape configuration, dashboards, and alerts. Ordinary startup continues to run the original engine stack without these monitoring services or the sampler.

This keeps basic setup small and lets evaluators opt into the failure-diagnosis demonstration. The maintenance cost is another supported configuration: profile behavior, merged Compose settings, management health checks, discovery, provisioned assets, and monitoring image versions need verification. Monitoring-enabled workers expose only an internal management listener; the overlay publishes Grafana and Prometheus on loopback. Anonymous Grafana viewing is a local evaluation convenience. A shared deployment would require its own access controls and network configuration.

The Compose overlay is a Compose-specific deployment definition. Kubernetes cannot consume it unchanged. The application image, instrumentation, metric names, structured events, and much of the dashboard/query configuration can be reused. Kubernetes would need deployments, configuration/storage, probes, internal management networking, and per-pod scrape discovery. An operator-based setup could use ServiceMonitor/PodMonitor resources; these are deployment choices, not dependencies of the Java engine. The fault-demonstration script would also need adaptation. Kubernetes overlays express a similar configuration idea with different resources and tooling.

The current deployment has one API sampler. Replicating the API without revisiting collection would repeat the queries and expose multiple copies of the same database snapshot. A designated sampler or deliberate query/aggregation policy is needed before that deployment change.

### Health, activity, and progress require different signals

Verification exposed an important distinction: a coordinator pass can return successfully after skipping locked work while dependency-ready steps remain unpromoted. A recent successful pass proves activity, not progress. We therefore observe both loop age and durable state awaiting promotion. Loop age uses monotonic elapsed time with a startup grace period; backlog and expired-claim observations independently expose delayed work.

Likewise, an UP management endpoint does not prove database readiness or workflow progress. DNS discovery may remove a dead worker target rather than leave an `up=0` series, so discovered worker count and total capacity matter alongside target health. Freshness checks prevent a database outage from producing an unsupported backlog diagnosis.

The isolated walkthrough validates these distinctions through saturation, worker death and replacement, a controlled coordination stall while API health stays UP, database pause and stale observations, monitoring-backend outage while execution continues, and discovery after worker scale-out. The [runbook](OBSERVABILITY.md) records commands, expected signals, alert thresholds, and verification results. These local checks establish behavior under the demonstrated faults; they do not establish production capacity or availability.

### Accepted boundaries and triggers to revisit

Milestone 10a prioritizes explainable failure diagnosis, reproducible verification, and limited execution-path complexity. It accepts sampled state, collection overhead, best-effort telemetry, and local operational scope. Monitoring does not decide claims, retry eligibility, fencing, or workflow outcomes. Instrumentation failures are isolated from execution decisions, and stopping Prometheus/Grafana does not stop workflows.

Next changes should follow a concrete requirement or measurement: tune queries/indexes or collection intervals for observed cost; consider summaries/projections for sustained read pressure; add centralized logs/tracing when cross-process diagnosis needs them; define production SLOs and alert routing with operators. Database high availability, backups, Kubernetes deployment, and broader operational hardening remain separate work.

## Reviewer-facing rationale

> We selected a custom Java engine backed by PostgreSQL to make the assignment's coordination and recovery behavior explicit and testable. Workflow execution requires coordinated updates across related workflow, cycle, step, dependency, and attempt records. PostgreSQL transactions, constraints, row locking, SQL queries, and JSONB provide a manageable implementation of those requirements, while Docker Compose enables reproducible testing of actual components. Keeping work eligibility and execution state in one transactional system avoids a separate database-to-broker consistency protocol initially. Step Functions is a credible managed alternative for business orchestration; DynamoDB and SQS could support a custom cloud-native engine with different modeling and dispatch responsibilities. Our choice prioritizes correctness, inspectability, and reproducibility within the assignment scope, while accepting polling, database contention, and operational limitations.

## Points to revisit

- Whether the deployment goal is operating a custom engine or orchestrating business processes with a managed service.
- How a generic DAG and our recovery/history semantics would map to Step Functions.
- Workload measurements that might justify different storage or work-delivery infrastructure.
- Availability, security, backup/recovery, and cost requirements for a deployment beyond local evaluation.
- Whether measured observation-query cost or freshness requirements justify a summary/projection, replica, or different sampling policy.

This document records the discussion and the separately approved milestone 10a decisions. Further optional implementation requires its own scope and approval.
