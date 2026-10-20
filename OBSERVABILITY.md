# Observability and Failure Diagnosis

Milestone 10a adds an optional local monitoring deployment: application metrics and structured logs, periodic database observations, Prometheus, a provisioned Grafana dashboard, alert rules, and isolated failure demonstrations. Workflow ownership, schema, retries, and public API contracts are unchanged.

## Start and inspect

```sh
docker compose -f docker-compose.yml -f compose.observability.yml up --build -d --wait
```

- Dashboard: [Workflow Engine — Operational Overview](http://localhost:3000/d/dwe-overview).
- Prometheus: [queries and targets](http://localhost:9090); open **Alerts** for pending/firing rules.
- Grafana is provisioned automatically with a read-only anonymous Viewer for local evaluation. Both published monitoring ports bind to loopback. This configuration is not suitable for a shared/public deployment.
- Override host ports with `GRAFANA_PORT` and `PROMETHEUS_PORT` when needed. Monitoring data has separate named volumes; Prometheus retains 24 hours.

Use both Compose files for subsequent operations on this deployment:

```sh
docker compose -f docker-compose.yml -f compose.observability.yml logs --tail=100 api worker
docker compose -f docker-compose.yml -f compose.observability.yml exec api \
  curl --fail http://localhost:9091/actuator/health/readiness
docker compose -f docker-compose.yml -f compose.observability.yml down
```

`down` retains database and monitoring volumes. The ordinary `docker-compose up` command continues to start only the original engine stack, with no worker HTTP listener and no database sampler. The same application image contains instrumentation in both configurations.

Monitoring mode enables management-only port 9091 inside application containers; it is not published to the host. Workers have no business HTTP connector. API business routes remain on host port 8080; Actuator health moves to management port 9091, and the overlay adjusts the container health check. Liveness indicates process availability; readiness additionally checks the database. Neither establishes that coordination is making progress.

## Read the dashboard

| Row | Interpretation |
| --- | --- |
| Availability and capacity | Check sample freshness, API targets, worker targets, and occupied slots first. DNS discovery can remove a dead target, so a lower worker count matters even if remaining targets are UP. |
| Work waiting and progress | Runnable backlog excludes unsatisfied dependencies and future retry backoff. Oldest wait is measured at the last sample. Awaiting-promotion counts dependency-satisfied pending steps without eligibility, including roots. |
| Worker capacity and execution | Compare occupied slots against capacity, committed attempt-outcome rates, handler duration, and retry scheduling. A completed handler may still fail to persist its result. |
| Ownership, recovery and infrastructure | Correlate expired claims/age with renewal errors, ownership loss, recovery activity, database-control latency, and connection-pool pressure. |
| Alerts and resources | Firing alerts link diagnosis to the rules below. JVM charts help explain resource pressure; they are not workflow success indicators. |

An empty queue chart can mean **no current observation**. Check the freshness tile before interpreting it as an empty queue. An idle rate chart can have no series until the first event. Worker-instance filters apply to worker/resource panels; database panels intentionally show the API sampler independently.

## Collection and guarantees

- **Process observations:** Micrometer counters/timers and local capacity gauges. Labels use fixed event, operation, outcome, and loop values. Execution/step/attempt/worker identities are diagnostic log fields, not custom metric labels. Prometheus adds the scrape instance and role.
- **Committed transitions:** lifecycle counters/events are emitted by transaction `afterCommit` callbacks only for newly applied transitions. Rollbacks and duplicate/rejected result reports do not count another completion. Uncertain responses may be reconciled without rerunning the handler. Telemetry is best effort: process death can lose observations; database history remains authoritative.
- **Durable observations:** only the API profile samples the database when `observability.database-enabled=true`. Two read-only aggregate statements use one repeatable-read transaction and database time. Sampling runs on its own single thread and a separate Hikari pool capped at one connection, with no workflow-row locks. Prometheus scrapes read an immutable in-memory snapshot and never issue database queries.
- **Costs and timing:** `observability.sample-interval` defaults to 5s; `observability.query-timeout` defaults to 500ms per statement/lock wait. Connection acquisition is capped at 500ms, with JDBC network/connect timeouts inherited from the database URL. These are stage budgets, not a strict end-to-end deadline. Collection never overlaps or catches up in a burst. Queries filter active workflows/steps and expired running leases rather than computing historical totals. Physical query plans and cost depend on database statistics, indexes, and workload; measure them before production use.
- **Freshness:** failed collection retains the previous snapshot and successful timestamp, but sets sample success to zero. Before the first success, state gauges are NaN. Dashboard queue/lease queries and related alerts require success plus a sample younger than 15s. Failure is not exported as zero backlog. The monitoring backend does not control workflow progress.
- **Time-derived state:** a retry can become ready or a lease expire without any new write. Counts therefore use database time during sampling. Cached ages are ages at collection time, not continuously recomputed values. Awaiting-promotion has no invented readiness timestamp.

Custom metric families are `dwe_events_total`, `dwe_operation_duration_seconds_*`, `dwe_worker_slots_*`, `dwe_loop_*`, `dwe_steps_*`, `dwe_leases_*`, and `dwe_database_sample_*`. Standard JVM, HTTP, and application Hikari metrics are supplied by Actuator. Handler timers exclude result reporting; operation timers include failed calls. Loop success means the pass returned successfully, not that it advanced every workflow. Loop age uses monotonic elapsed time since success, or initialization before any success, to allow a startup grace period.

Structured lifecycle events include submission, manual retry, claim, completion/failure, retry scheduling, abandonment, and workflow finalization. They include applicable identities and outcomes, but do not log submitted parameters or result JSON. Routine successful heartbeats are measured rather than logged individually. Logs are available through Compose; a centralized log/tracing backend is outside this sub-milestone.

## Alerts and runbook

Rules are in [monitoring/alerts.yml](monitoring/alerts.yml). Their thresholds are configurable demonstration budgets, not production SLOs. Scrape, sampling, evaluation, and `for` windows add detection latency. Alerts appear in Prometheus and the dashboard; no external notifications are sent.

| Alert/symptom | Diagnose | Expected action/recovery |
| --- | --- | --- |
| `ReadyWorkWaiting`: oldest eligible work >30s for 15s | Compare occupied/capacity gauges and discovered workers. Inspect poll/database-control errors. | Full slots suggest capacity pressure; spare slots suggest claiming/connectivity problems. Available workers should drain the queue. |
| `ExpiredClaimsWaiting`: oldest expired lease >15s for 15s | Inspect recovery loop activity, database errors and lock pressure. Include failed workflows' unfinished attempts. | Recovery settles abandonment, then schedules backoff or failure according to persisted allowance. |
| `ControlLoopOverdue`: no successful coordinator/recovery pass for >30s for 15s | Inspect errors and database locks/connectivity. Compare awaiting-promotion and expired-claim panels. | Restore the blocked dependency/process. An UP health endpoint is compatible with stalled orchestration. |
| `DependencyPromotionWaiting`: dependency-ready pending steps remain unpromoted for 30s | Inspect coordinator logs and workflow/step locks. A pass can return successfully after skipping busy workflows. | Release the underlying blockage; a later pass promotes the work. This checks progress independently of pass activity. |
| `DatabaseObservationsUnavailable`: collection failed or stale for 15s | Check database readiness, connectivity and sample duration/errors. Do not infer that a cached backlog is current. | Collection freshness returns after database access recovers. Queue alerts are suppressed while observations are unavailable. |
| `EngineApiUnreachable`: management scrape fails for 15s | Inspect API process availability and management networking. | Restore the endpoint; missing metrics are not proof the database or workers stopped. |
| Worker count/capacity falls | Check worker containers and discovery targets; a vanished DNS target may disappear rather than remain `up=0`. | Restore worker capacity. Existing claims may require expiry/backoff before replacement. |

During database loss, control-loop alerts can also fire because those passes really are failing. The unavailable-sample signal explains why queue values cannot currently support a backlog diagnosis.

## Reproducible failure demonstration

```sh
python3 scripts/verify-observability.py
```

The script builds a unique application image and isolated Compose project with disposable host ports and volumes. It verifies provisioning, distinct worker scrapes, management-only workers, saturation/backlog, actual worker death and replacement, a controlled coordinator stall while API health remains UP, database pause/stale observations/alerts, monitoring-backend outage without stopping workflow completion, and worker scale-out discovery. It uses ordinary engine lease timings; allow several minutes plus image/build startup.

Faults and cleanup affect only the isolated project. Database pause and step-row locks are undone even on failure; the script removes its own stack/volumes/image. It does not stop the normal local project. Do not reproduce faults against a deployment containing valuable work.

To retain the successful demonstration for visual inspection:

```sh
python3 scripts/verify-observability.py --keep
```

The script prints dashboard/Prometheus URLs and a project-specific cleanup command, retaining configuration under the printed temporary directory. Follow that command when finished, then remove its temporary application image/configuration as desired. Retained ports differ from the normal local defaults.

Unit/PostgreSQL tests cover failure isolation, bounded metric labels, control-loop activity, rollback/duplicate/stale-result accounting, time-based retry eligibility, failed collection retaining cached values, nonblocking gauge reads, and expired claims on failed workflows. Alert tests exercise fresh backlog alerts and suppression of stale backlog observations:

```sh
./mvnw clean verify
docker run --rm -v "$PWD/monitoring:/etc/prometheus:ro" \
  --entrypoint /bin/promtool prom/prometheus:v3.14.0 check config /etc/prometheus/prometheus.yml
docker run --rm -v "$PWD/monitoring:/etc/prometheus:ro" -w /etc/prometheus \
  --entrypoint /bin/promtool prom/prometheus:v3.14.0 test rules alerts.test.yml
```

## Portability and limits

Instrumentation, metric names, structured events, and most dashboard queries are independent of Compose. This overlay and the fault script are Compose-specific; Kubernetes would require its own deployments, internal management exposure, per-pod discovery, configuration/storage, and failure tooling. A replicated API deployment would also need a single designated database sampler or query changes to avoid double-counting snapshots. No claim of multi-coordinator deployment verification, production throughput, guaranteed alert delivery, exactly-once telemetry, or database high availability is made here.

## Verification checkpoint

On 2026-10-19, clean Java 21 Maven verification passed 60 unit tests and 113 PostgreSQL integration tests with no failures, errors, or skips. Prometheus configuration/rule tests, the complete isolated observability walkthrough, and the original delivery/restart smoke test passed. All 25 dashboard expressions executed successfully, and the provisioned dashboard was visually inspected. Successful collection during the small demo workload had an approximately 16ms p95 histogram estimate, including collection overhead; larger active workloads and production query plans remain unbenchmarked. These are local results using ordinary dependency/image caches, not fresh-clone or production-capacity verification.
