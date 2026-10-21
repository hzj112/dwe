# Implementation Plan and Environment Validation

## Status and review boundary

Current status: core milestones 1–7 are complete, with milestone 7 committed as `c403b14`. The separately approved observability/failure-diagnosis sub-milestone 10a is recorded in [Milestones.md](Milestones.md) and [OBSERVABILITY.md](OBSERVABILITY.md). The environment observations, proposed implementation tree, and earlier checkpoint statements below are historical records. Current behavior and commands are described in README.md and DESIGN.md; Milestones.md governs the roadmap.

Prepared on 2026-10-17 (America/New_York) for review before application code generation, with environment results updated after Docker Desktop was started. Milestone 2 was subsequently authorized and implemented. The environment-validation sections below preserve the observations made before implementation; the checkpoint records the newer project-specific results. No global shell-profile changes have been made.

The architecture and contracts in [DESIGN.md](DESIGN.md) govern implementation. [PROJECT_DESCRIPTION.md](PROJECT_DESCRIPTION.md) defines the must-have scope, and [AGENTS.md](AGENTS.md) requires one approved milestone at a time.

Milestone 1 (design) is recorded. Milestone 2 is the first proposed coding scope. Later milestones below describe the remaining delivery path; listing them does not authorize starting them automatically. At each boundary, report changes, actual test results, limitations, and remaining risks; recommend a checkpoint commit and wait for approval before the next milestone. Do not commit or push without explicit approval.

The reviewed roadmap in [Milestones.md](Milestones.md) now supersedes this document's original combined milestones 3–5. It divides the remaining required work into milestones 3–7 and records optional extensions separately. Milestone 2 was committed as `91600a6`. Milestone 3 was subsequently authorized; its checkpoint below records the current work without authorizing milestone 4.

### Milestone 3 checkpoint

- Compose defaults to two worker replicas using the same image, with two handler slots per process.
- `examples/parallel.json` demonstrates two branches and a join; README includes submission, status, and read-only attempt-inspection commands.
- Three new integration tests exercise gated branches/joins, competing worker runtimes, and per-worker capacity. Six more PostgreSQL scenarios exercise lifecycle races and failure propagation.
- Focused verification passed all nine new scenarios. Full clean verification passed 24 unit tests and 30 integration tests with no failures, errors, or skips.
- The image built and isolated Compose startup passed with PostgreSQL, API, and two worker containers. HTTP checks completed sequential, parallel, and four-branch workflows; database records confirmed branch overlap, correct join ordering, one completed attempt per step, and two simultaneous branch claims per worker in the four-branch case.
- No production Java behavior, public API, schema, or dependency changes were needed. The existing workflow guard and conditional ownership checks passed the added coverage.
- Milestone 3 is complete. Recovery/connectivity behavior remains milestone 4, full retries/manual retry milestone 5, and listing/history APIs milestone 6. These have not been started.

### Milestone 4 checkpoint

Milestone 3 was committed as `1136fc8`. Milestone 4 was subsequently authorized, implemented, and verified.

- Added the recovery scheduler and validated `engine.recovery-interval` setting (default 5 seconds). Recovery scans bounded candidate batches and uses the existing workflow/step lock ordering and live database time.
- Expired attempts become `ABANDONED`; persisted backoff and attempt limits govern replacement. Expired siblings on failed workflows are settled without retry or loss of the original error.
- Added read-only terminal-outcome reconciliation after uncertain result writes, plus distinct local cancellation diagnostics.
- Added five local lease tests and seventeen PostgreSQL scenarios for recovery, uncertainty, and shutdown. Clean Maven verification passed 29 unit tests and 47 integration tests without failures, errors, or skips.
- Added `examples/recovery.json` and the portable, commented `scripts/verify-recovery.py`. It built the image and isolated two-worker stack, then passed actual worker-kill, worker-partition, and database-pause checks with durable replacement and backoff evidence. Its own disposable containers, network, volume, and image were cleaned up.
- No schema or public API changes were required. Complete retry/manual-retry acceptance remains milestone 5, listing/history APIs milestone 6, and final delivery milestone 7; none has started.

### Milestone 5 checkpoint

Milestone 4 was committed as `457e0ec`. Milestone 5 was subsequently authorized, implemented, verified, and committed as `8bd788e`.

- Added stateless `FAIL_UNTIL_ATTEMPT` handler with validated failure count and optional permanent-error classification, using persisted lifetime attempt numbers.
- Added the manual-retry service, `POST /api/v1/executions/{id}/retry`, stable conflict responses, and atomic new-cycle creation under the existing workflow guard. Completed outputs, policies, original configuration, earlier history, and lifetime counters are preserved.
- Added automatic-retry and manual-retry integration tests, a handler unit test, and a small shared worker test harness. Focused verification passed 23 new PostgreSQL scenarios; full clean verification passed 42 unit tests and 70 integration tests without failures, errors, or skips.
- Added automatic, manual, and permanent-failure examples plus commented `scripts/verify-retries.py`, reusing the isolated recovery-check fixture. Image build and container checks passed for all examples and actual API/worker restart during a persisted backoff; temporary resources were cleaned up.
- The five-table schema and dependencies remain unchanged. Listing/history APIs remain milestone 6 and final delivery milestone 7; neither has started.

### Milestone 6 checkpoint

Milestone 5 was committed as `8bd788e`. Milestone 6 was subsequently authorized, implemented, verified, and committed as `a12f473`.

- Added execution listing and cycle/attempt history routes, DTOs, read-only persistence queries, and versioned resource/filter-bound continuation tokens.
- Kept the existing schema, indexes, dependencies, and execution behavior. Each request reads a consistent snapshot; separate pages are not a frozen history snapshot.
- Added 14 cursor unit cases and 37 PostgreSQL/HTTP scenarios for pagination, timestamp ties, filters, errors, multi-cycle records, and unchanged stored state. Full clean verification passed 56 unit tests and 107 integration tests without failures, errors, or skips.
- Added commented `scripts/verify-history.py` using the isolated Compose fixture. Image build and public API checks passed, including preserved prior history after manual retry; temporary resources were cleaned up.
- Updated README and DESIGN with usage, pagination semantics, rationale, and limitations. Milestone 7 final delivery verification has not started.

### Milestone 7 checkpoint

Milestone 6 was committed as `a12f473`. Milestone 7 was subsequently authorized, implemented, and verified; changes remain uncommitted for review.

- Added commented `scripts/verify-delivery.py` using ordinary engine settings. Its Compose `up` builds a missing unique image, runs shipped examples, checks branch overlap/join ordering and one recorded attempt with two workers available, and verifies application/database restarts.
- Compared every column of the five execution tables before and after restarts, including full container recreation with its database volume retained. A queued execution survived recreation and completed under its original ID/configuration with one attempt per step.
- Extended only the existing disposable fixture's timing option; no Java implementation, schema, API contract, or dependency changes were needed.
- Completed README architecture/API diagrams and examples, configuration/troubleshooting guidance, and a reproducible requirement-to-evidence matrix. Consolidated DESIGN's historical checkpoints into a current implementation summary while preserving decision rationale, alternatives, guarantees, and deferred work.
- Final verification on 2026-10-18 used a fresh local clone of `a12f473` plus the pending milestone 7 files. Clean Maven verification passed 56 unit tests and 107 PostgreSQL integration tests without failures/errors/skips. Delivery, recovery, retry, and history scripts each built isolated images/stacks and passed, including actual process death, worker/database partition, database pause, and restart during retry backoff. Temporary stacks/volumes/images and the checkout were cleaned up.
- Ordinary Maven/Docker caches were reused; original build artifacts, `.env`, and IDE settings were not copied. This is local candidate verification before commit, not evidence of an already-published milestone 7 clone, another platform, empty caches, or production load capacity. Optional milestones have not started; recommend a checkpoint commit after review.

### Milestone 2 checkpoint

The proposed project tree is generated, with an additional shared integration-test support class to avoid duplicating PostgreSQL/HTTP setup. Maven dependencies and the official Maven Wrapper were downloaded. The implementation and run instructions are documented in [README.md](README.md), and [DESIGN.md](DESIGN.md) now distinguishes this slice from the complete target architecture.

| Gate | Actual result |
| --- | --- |
| Clean Java 21 / Maven Wrapper build | `./mvnw -B -ntp clean verify` passed using the recorded Java 21 home. |
| Unit tests | 24 passed; no failures, errors, or skips. |
| PostgreSQL integration tests | 21 passed through Testcontainers against pinned PostgreSQL 18.6; no failures, errors, or skips. |
| Database foundation | Fresh migration, repeated migration, submission rollback, relational/JSON constraints, conditional ownership, expired/stale write rejection, duplicate outcomes, supported backoff timestamp boundary, and failure propagation passed. |
| Handler/runtime foundation | Sequential execution, isolated handler context, invalid-output rejection, and heartbeat renewal beyond the original lease passed. |
| Submission schema | Extracted Draft 2020-12 schema validated; the sequential sample was accepted and five invalid structural payloads were rejected using a temporary external validator. |
| Docker image | Shared API/worker image built successfully from pinned Java 21 JDK/JRE bases with Maven inside the build stage. |
| Compose | Parsed successfully; exact `docker-compose` command started PostgreSQL, healthy API, and a running worker in an isolated verification project. Worker had no HTTP listener. |
| HTTP/restart smoke test | Sample completed; execution detail, timestamps, attempt count, and final output remained identical after API/worker restart against the same database. |

The sandbox denied some Docker/socket and dependency operations; the same operations passed with approved access outside it. Integration tests were not skipped to work around those restrictions. The isolated Compose verification containers and network were removed afterward, freeing the development ports; the final verification database volume is retained under project `dwe-m2-check`. These results complete milestone 2 only; milestones 3–5 have not been started. Expired-attempt recovery and the remaining APIs are explicitly incomplete. No commit has been made by the agent.

## Assignment and technology alignment

| Requirement or choice | Implementation commitment |
| --- | --- |
| Java required by assignment | Target Java 21 for compilation, local execution, and container runtime. |
| Maven required by technical constraints | Use `pom.xml`, the Spring Boot Maven plugin, and a pinned Maven Wrapper. No Gradle build. |
| Docker and Docker Compose required | Deliver `Dockerfile` and `docker-compose.yml`; verify the exact `docker-compose up` command as well as Compose v2 syntax. |
| Framework is an assignment choice | Use Spring Boot, as requested and already specified in AGENTS.md. |
| Database is an assignment choice | Use PostgreSQL, as already agreed; it also provides durable task discovery and coordination. |
| AWS optional | Do not require an AWS account, LocalStack, or cloud services. |
| Per-step type/action required | Retain `actionType`; pre-register Java handlers at startup, with business-independent orchestration. |
| Required delivery evidence | Working code, README, DESIGN, sample workflows, reproducible tests/demos, and reviewed checkpoint commits. |

Keep advanced scheduling, arbitrary unregistered execution, cancellation APIs, compensation, and dashboards deferred. Focus on a complete local system and the required distributed execution guarantees.

## Environment validation results

Checks below were executed locally. Installed clients are distinguished from working services; a successful version command does not establish database/container readiness.

| Check | Observed result | Readiness |
| --- | --- | --- |
| Host | macOS 26.6.2, Apple Silicon / arm64. | Plan native ARM-compatible container images. |
| Java runtime | Eclipse Temurin OpenJDK 21.0.12.1 LTS. | Available. |
| Java compiler | `javac 21.0.12.1`. | Available. |
| Maven | Apache Maven 3.9.16. | Available. |
| Maven's default JVM | Homebrew OpenJDK 26.0.2.1, different from the shell's Java 21. | Set Java 21 explicitly for project builds. |
| Maven with explicit Java 21 | `mvn -version` reported Temurin 21.0.12.1. A standalone Maven artifact-resolution check also completed successfully under that JDK. | Verified selection and artifact download; no application project build attempted. |
| Docker CLI | Docker 28.4.0. | Installed. |
| Compose v2 | `docker compose version`: v2.39.4-desktop.1. | Installed. |
| Assignment command spelling | `docker-compose version`: v2.39.4-desktop.1. | Installed; required command spelling is supported. |
| Docker Desktop | Running; selected context is `desktop-linux`. | Verified daemon connectivity. |
| Docker daemon | Server 28.4.0, Linux/aarch64, 10 CPUs, approximately 7.65 GiB memory reported. | Ready for container-based environment checks; successful container executions recorded below. |
| Maven Central | HTTPS HEAD returned 200. Maven dependency plugin 3.9.0 then resolved the Spring Boot 4.1.1 parent POM successfully in a temporary local repository under Java 21. | Real Maven artifact resolution passed; the application's dependency graph remains a milestone 2 check. |
| Agent sandbox | Maven Central DNS and Docker image-pull socket access were blocked inside the sandbox; approved checks outside it succeeded. | Later dependency/container operations may need approved access; these sandbox failures are not host environment failures. |
| Git | Git 2.54.0; working tree was clean before creating this document. | Available. |
| Default host ports | Checks outside the agent sandbox returned connection refused on 127.0.0.1:8080 and :5432. The sandbox itself denied socket probes. | No listener detected outside the sandbox; this does not reserve either port. |
| Disk space | Approximately 11 GiB available after the image pulls and Maven check, compared with approximately 13 GiB initially. | Recheck before larger image builds/test runs; application build footprint remains unmeasured. |

The Java home verified with Maven is:

```text
/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
```

Use that JDK in the IDE and as JAVA_HOME for project Maven commands. During scaffolding, configure Maven to compile for release 21 and enforce the selected JDK so the current Homebrew default does not silently change the build environment. Do not edit global shell configuration as part of code generation. The Maven Wrapper pins Maven, not the Java runtime.

### Remaining environment gates

| Gate | Follow-up result | Status |
| --- | --- | --- |
| 1. Reach the selected Linux Docker daemon | `docker info` succeeded after Docker Desktop was started. | Passed. |
| 2. Pull and run native ARM64 Java/PostgreSQL images | PostgreSQL 18.6 and Temurin Java 21 JDK/JRE images were pulled, inspected as `linux/arm64`, and run successfully. | Passed. |
| 3. Verify a disposable PostgreSQL server and query | Health reached `healthy`; an authenticated TCP connection returned server version 18.6, database `dwe_env`, and `SELECT 1` returned `1`. | Passed; temporary container removed. |
| 4. Resolve the application graph, run Testcontainers integration, validate project Compose | A real standalone Maven download passed. There is still no application POM, integration test, or project Compose file to execute. | Project-specific verification remains for milestone 2. |

The pre-implementation environment gates now pass. Java 21 must still be selected explicitly because Maven's unconfigured default remains Java 26; no global Java configuration was changed. No additional host tool installation is currently required. Testcontainers discovery, application migrations, the complete dependency graph, Dockerfile builds, and project Compose startup are not claimed as verified before those project files exist. A host PostgreSQL installation or host `psql` is unnecessary.

### Follow-up check details

| Image | Platform | Executed check |
| --- | --- | --- |
| `eclipse-temurin:21-jdk` | linux/arm64 | Container reported `aarch64`; Java and javac both reported Temurin 21.0.12.1. |
| `eclipse-temurin:21-jre` | linux/arm64 | Container Java runtime reported Temurin 21.0.12.1. |
| `postgres:18.6` | linux/arm64 | Healthy PostgreSQL 18.6 server, authenticated TCP SQL check, and data directory `/var/lib/postgresql/18/docker`. |

The PostgreSQL check used a uniquely named container with no published ports, no persistent/host volume mounts, and a tmpfs database under `/var/lib/postgresql`. Its network was isolated; SQL ran through `docker exec` against the container's loopback TCP interface. The container was stopped and automatically removed after successful checks. Java check containers also removed themselves. No unrelated containers, databases, or volumes were modified. Downloaded images remain available in Docker's local cache.

Observed image digests, recorded for reproducibility when preparing the project Dockerfile and Compose configuration:

| Image | Pulled digest |
| --- | --- |
| `postgres:18.6` | `sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336` |
| `eclipse-temurin:21-jdk` | `sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d` |
| `eclipse-temurin:21-jre` | `sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99` |

The Maven check invoked dependency plugin 3.9.0 `get` for `org.springframework.boot:spring-boot-starter-parent:4.1.1:pom`, without project sources and with `transitive=false`. It downloaded the plugin/dependencies and target POM into a temporary repository and returned `BUILD SUCCESS` under Java 21 outside the sandbox. This verifies Maven/JDK repository access, not compilation or resolution of the future application's dependencies.

## Proposed implementation baseline

| Area | Proposed baseline and reason |
| --- | --- |
| Runtime/build target | Java 21; already installed and consistent with the intended container runtime. |
| Build tool | Maven 3.9.16, pinned by Maven Wrapper, with `mvn` commands also supported. |
| Framework | Spring Boot 4.1.1, pinned through its Maven parent. Official requirements include Java 17+ through 26 and Maven 3.6.3+, so the proposed Java 21/Maven 3.9.16 combination fits. |
| HTTP/configuration | Spring MVC, Bean Validation, configuration properties, and Jackson managed by Spring Boot. Preserve the JSON-object contract; use the selected release's consistent Jackson APIs. |
| Persistence | Spring JDBC and explicit SQL/transactions, PostgreSQL JDBC driver, and Flyway migrations. This keeps claims and fencing visible rather than relying on ORM lifecycle behavior. |
| Database | PostgreSQL 18.6; `postgres:18.6` was pulled and run successfully on ARM64. Migration compatibility remains a project integration check. Use one named development volume; tests use disposable databases. |
| Testing | JUnit Jupiter and Spring test support, PostgreSQL through Testcontainers. Use Spring Boot dependency management for compatible versions; verify actual integration rather than inferring it from version checks. |
| Process roles | One Maven module and application artifact, with API/coordinator and worker runtime profiles. Milestone 2 exercises one worker; milestone 3 scales to the agreed two-worker default. |

Spring Boot version compatibility and its dependency management were checked against the official [system requirements](https://docs.spring.io/spring-boot/system-requirements.html) and [build-system guidance](https://docs.spring.io/spring-boot/reference/using/build-systems.html). PostgreSQL's [supported version list](https://www.postgresql.org/support/versioning/) lists 18.6. The official [PostgreSQL container documentation](https://github.com/docker-library/docs/blob/master/postgres/README.md) governs the chosen image's volume layout; verify the PostgreSQL 18 layout instead of copying an older-major mount convention. Container-backed tests require a working compatible runtime, as documented by [Testcontainers](https://java.testcontainers.org/supported_docker_environment/).

These are proposed implementation selections for review, not changes to the approved architecture or evidence of a successful build. Pin container base image tags/digests when preparing the Dockerfile and retain version consistency between local tests and Compose.

## Milestone 2: minimal end-to-end implementation

Implement in this order, checking each foundation before building on it:

1. **Scaffold the Maven application.** Create the single-module Java 21/Spring Boot project, Maven Wrapper, configuration properties, role profiles, and minimal Docker/Compose setup for local PostgreSQL and eventual application processes. Extract the approved submission schema into a resource. Configure unit tests under Surefire and integration tests under Failsafe.
2. **Create durable storage.** Add a Flyway migration for the five agreed tables, constraints, and initial indexes. Verify fresh database creation, foreign keys, JSON-object rules, attempt classification, and migration restart behavior against actual PostgreSQL.
3. **Implement submission validation and handler registration.** Add definition/request models, per-field retry defaults, DAG and registry validation, handler/context/error contracts, and simple predefined `ECHO` and `DELAY` demo handlers. Preserve submitted configuration while persisting normalized effective settings.
4. **Implement the first API slice.** Provide submission, execution detail/status, and individual step status routes with the agreed receipt, Location header, and error envelope. Build only those routes initially; do not claim listing, history pagination, or manual retry is already complete.
5. **Implement the execution foundation.** Add the shared transactional store, workflow guard, conditional claims, fencing tokens, ownership renewals, outcome persistence, coordinator advancement, and one bounded worker executing a sequential workflow. Do not use process-local ownership or bypass claims just because only one worker is under test.
6. **Verify and document the slice.** Run unit and PostgreSQL integration checks, exercise a sequential sample through HTTP, restart application processes against the same database, inspect retained results/history, and update README/DESIGN with actual behavior and current limitations.

**Acceptance:** a validated sequential workflow can be submitted, claimed, executed, queried, and inspected after an application restart. Unknown actions and invalid definitions are rejected before persistence. Duplicate/stale ownership writes are rejected. API/coordinator and worker profiles start independently. Maven verification passes with Docker running.

The initial slice is intentionally incomplete relative to the final submission: multi-worker race/recovery coverage belongs to milestone 3, and full retry/API coverage belongs to milestone 4. Preserve the agreed contracts as those paths are added; never mark work completed merely to compensate for missing behavior. Stop for review after this milestone.

### Proposed milestone 2 file tree

Reviewed file structure for milestone 2, now generated. Existing documentation is shown for orientation. The implementation also adds `support/IntegrationTestSupport.java` to share integration-test setup. Prefer direct implementations and focused records/classes; avoid introducing interfaces that merely wrap one implementation.

```text
dwe/
├── AGENTS.md
├── PROJECT_DESCRIPTION.md
├── DESIGN.md
├── Plan.md
├── README.md
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── .gitignore
├── .dockerignore
├── .env.example
├── Dockerfile
├── docker-compose.yml
├── examples/
│   └── sequential.json
└── src/
    ├── main/
    │   ├── java/com/athenahealth/dwe/
    │   │   ├── WorkflowEngineApplication.java
    │   │   ├── api/
    │   │   │   ├── ExecutionController.java
    │   │   │   ├── ExecutionDtos.java
    │   │   │   └── ApiExceptionHandler.java
    │   │   ├── config/
    │   │   │   ├── EngineProperties.java
    │   │   │   └── RuntimeConfiguration.java
    │   │   ├── domain/
    │   │   │   ├── WorkflowDefinition.java
    │   │   │   ├── StepDefinition.java
    │   │   │   ├── RetryPolicy.java
    │   │   │   ├── WorkflowStatus.java
    │   │   │   ├── StepStatus.java
    │   │   │   ├── AttemptStatus.java
    │   │   │   └── StepClaim.java
    │   │   ├── service/
    │   │   │   ├── WorkflowValidationService.java
    │   │   │   └── WorkflowSubmissionService.java
    │   │   ├── persistence/
    │   │   │   ├── WorkflowStore.java
    │   │   │   └── WorkflowQueries.java
    │   │   ├── engine/
    │   │   │   └── WorkflowCoordinator.java
    │   │   ├── worker/
    │   │   │   ├── WorkerRuntime.java
    │   │   │   └── LeaseManager.java
    │   │   └── handler/
    │   │       ├── StepHandler.java
    │   │       ├── StepContext.java
    │   │       ├── StepExecutionException.java
    │   │       ├── StepValidationException.java
    │   │       ├── HandlerRegistry.java
    │   │       └── demo/
    │   │           ├── EchoHandler.java
    │   │           └── DelayHandler.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-api.yml
    │       ├── application-worker.yml
    │       ├── schemas/
    │       │   └── execution-submission.schema.json
    │       └── db/migration/
    │           └── V1__create_workflow_schema.sql
    └── test/
        ├── java/com/athenahealth/dwe/
        │   ├── service/
        │   │   └── WorkflowValidationServiceTest.java
        │   ├── handler/
        │   │   ├── HandlerRegistryTest.java
        │   │   └── HandlerContractTest.java
        │   ├── support/
        │   │   └── PostgresTestConfiguration.java
        │   └── integration/
        │       ├── MigrationIT.java
        │       ├── WorkflowSubmissionIT.java
        │       ├── ClaimOwnershipIT.java
        │       └── SequentialWorkflowIT.java
        └── resources/
            └── application-test.yml
```

## Later milestones, reviewed separately

| Milestone | Work | Acceptance evidence |
| --- | --- | --- |
| 3: DAG concurrency and recovery | Parallel branches/joins, two competing workers, expired-attempt recovery, heartbeat starvation protection, and connectivity-failure behavior. | PostgreSQL transaction races demonstrate exclusive valid ownership, dependency barriers, stale-result rejection, recovery on active/failed workflows, and no new claims after failure commits. |
| 4: failure handling and API completion | Complete automatic backoff/allowances, permanent failures, new-cycle manual retry, listing, cycle/attempt history, cursor pagination, and uncertain-write reconciliation. Add a predefined handler that fails a configurable number of lifetime attempts. | Retry bounds/backoff persist across restart; permanent errors fail promptly; manual retry preserves completed outputs/history and waits for active attempts; all documented API routes and errors are verified. |
| 5: delivery verification | Finish Compose defaults, image/runtime settings, shutdown behavior, examples, reproducible recovery/connectivity demos, documentation, and fresh-clone verification. | `docker-compose up` starts the complete system; all must-have examples and Maven verification succeed using documented prerequisites. Record any remaining limitations explicitly. |

Some foundational failure/reporting code necessarily appears in milestone 2 so it can use the approved state model. Later milestones complete and verify those paths; do not weaken invariants or fake passing scenarios to preserve a milestone boundary. Any necessary architectural change requires discussion before implementation.

### Proposed additions in later milestones

Extend existing files where responsibilities already fit; the following lists likely new files rather than creating parallel implementations of the same operation.

```text
dwe/
├── examples/
│   ├── parallel.json
│   ├── retry.json
│   └── permanent-failure.json
├── scripts/
│   ├── submit-workflow.sh
│   ├── demo-worker-recovery.sh
│   ├── demo-connectivity-failure.sh
│   ├── demo-restart-persistence.sh
│   └── verify-local.sh
└── src/
    ├── main/java/com/athenahealth/dwe/
    │   ├── api/
    │   │   └── ExecutionHistoryController.java
    │   ├── service/
    │   │   └── WorkflowRetryService.java
    │   ├── engine/
    │   │   └── RecoveryCoordinator.java
    │   └── handler/demo/
    │       └── FailUntilAttemptHandler.java
    └── test/java/com/athenahealth/dwe/
        ├── domain/
        │   └── RetryPolicyTest.java
        └── integration/
            ├── DagSchedulingIT.java
            ├── WorkerCompetitionIT.java
            ├── LeaseRecoveryIT.java
            ├── ConnectivityFailureIT.java
            ├── RetryAndFailureIT.java
            ├── ManualRetryIT.java
            ├── OutcomeReconciliationIT.java
            ├── HistoryPaginationIT.java
            └── GracefulShutdownIT.java
```

## Verification and review gates

- Use focused tests during each change, then `./mvnw clean verify` at milestone boundaries. Configure Failsafe so PostgreSQL integration tests run during `verify`; `mvn test` alone is not the complete verification gate. The corresponding system-Maven command remains supported with Java 21 selected.
- Run PostgreSQL-specific invariants against actual PostgreSQL through Testcontainers; do not substitute H2 or silently skip these tests when Docker is unavailable. Unit-only checks may run separately, but report them as partial verification.
- Use deterministic synchronization and controllable clocks/handlers where possible. Test lease/transaction races explicitly; avoid timing-only assertions that merely happen to pass on a quiet laptop.
- Validate the extracted submission schema and its examples, while separately testing DAG, handler-registry, and effective-policy checks that JSON Schema does not enforce.
- Verify both API/worker role isolation and shared database behavior. Keep worker concurrency, heartbeat scheduling, connection budgets, and database-operation timeouts consistent with DESIGN.md.
- Verify persistent database volumes survive application/container restart. Never use volume deletion as a recovery demonstration; use isolated disposable resources for destructive cleanup in tests.
- Check Compose parsing, health/startup behavior, the exact assignment startup command, and sample HTTP calls. Initial setup needs dependency/image downloads; do not claim offline fresh-clone startup.
- At each checkpoint, update README and DESIGN with implemented behavior, actual results, and remaining scope. No code generation, service startup, installation, or commit is authorized merely by this plan's existence.
