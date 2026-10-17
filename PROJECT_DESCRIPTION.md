# Project Description: Distributed Workflow Engine

## Purpose

Build a working distributed workflow engine for the take-home assignment. The system will execute multi-step workflows reliably, preserve progress across restarts, and coordinate multiple workers when execution succeeds or fails.

The primary goal is to demonstrate distributed systems fundamentals, clear engineering decisions, and a complete, reproducible submission. Prioritize the required capabilities, and optional features come after the core behavior is implemented and verified.

This document captures the project scope and proposed overall plan. Detailed architecture, API contracts, and unresolved execution semantics will be addressed separately before their implementation.

## What the System Does

A user submits a workflow definition and its inputs through an API. A workflow is a directed acyclic graph (DAG): steps can depend on other steps, independent steps can run in parallel, and downstream steps wait for their dependencies to complete.

The engine creates a durable execution, identifies eligible steps, coordinates their execution by workers, records progress, and decides whether to continue, retry, complete, or fail the workflow. Users can query execution status and retry failed workflows.

For example, a workflow could fetch data, process and validate it in parallel, aggregate the results, and publish the output.

## Main Responsibilities

| Part | Responsibility |
| --- | --- |
| API | Accept workflow submissions and expose listing, status, history, and manual retry operations. |
| Engine/coordinator | Evaluate dependencies, make steps eligible, manage retries and recovery, and determine workflow outcomes. |
| Workers and step handlers | Acquire eligible tasks, execute business logic, and report results, errors, and execution progress. |
| Persistent storage | Preserve definitions, execution state, step results, ownership information, and attempt history. |
| Example workflows | Demonstrate the required behavior with concrete, runnable task implementations. |

These are logical responsibilities. They do not require a separate service or container for every part. Workers are expected to be long-running processes that accept tasks; creating a new worker container for every step is not required.

## Must-Have Requirements

### Workflow Definition and Execution

- Accept DAG workflow definitions with unique step identifiers, action types, input parameters, dependencies, and retry configuration.
- Validate definitions, including rejecting cycles and unknown dependencies.
- Execute sequential chains and independent branches concurrently.
- Start a step only after all its dependencies have completed successfully.
- Define a small execution contract for Java step handlers, including inputs, outputs, and error reporting.

### State and Visibility

- Persist workflow progress and execution history across application and worker restarts.
- Track workflow states: `PENDING`, `RUNNING`, `COMPLETED`, and `FAILED`.
- Track step states: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, and `RETRYING`.
- Record step attempts, timestamps, results, and errors.
- Expose workflow and step status and execution history through the API.

### Distributed Execution and Recovery

- Support multiple workers executing tasks concurrently.
- Coordinate competing workers so only one worker holds a valid claim for a step at a time.
- Detect abandoned execution attempts and recover from worker crashes.
- Handle lost connectivity and network partitions while preserving consistent execution state.
- Use leases and fencing, as required by `AGENTS.md`, to prevent stale workers from overwriting newer execution state.
- Document at-least-once task execution and the need for idempotent business operations. Exclusive valid ownership does not guarantee that external effects happen exactly once.

### Failures and Retries

- Support configurable retry limits and backoff; exponential backoff is recommended by the assignment.
- Fail a workflow when a step exhausts its retry allowance.
- Support manual retry of failed workflows while preserving execution history.
- Define and document which steps manual retry reruns and how retry allowances are counted.
- Mark a workflow completed only when all required steps complete successfully.

### API Operations

- Submit a workflow definition and start an execution.
- Query a workflow's status, individual step status, and execution history.
- List workflows.
- Retry a failed workflow.

A separate workflow editor, reusable-definition registry, and definition-versioning API are not required.

### Packaging, Verification, and Delivery

- Use Java, Maven, Docker, and Docker Compose, as required by the assignment.
- Use Spring Boot and PostgreSQL, as specified by the current project instructions in `AGENTS.md`.
- Start the complete local system and its required infrastructure with `docker-compose up`.
- Provide sample workflows for sequential execution, parallel execution, and failures with retries.
- Provide reproducible tests and demonstrations of worker competition, crash recovery, connectivity failures, and persistence across restarts.
- Test critical paths and distributed execution invariants.
- Verify the documented startup and examples from a fresh clone.
- Deliver a Git repository with meaningful, user-approved checkpoint commits.

## Optional Features and Scope Boundaries

The following assignment features are optional and should be considered only after the must-have scope is solid:

- AWS services, with local testing instructions if used.
- Cancellation of running workflows.
- Workflow versioning.
- Compensation or rollback steps.
- Metrics, distributed tracing, and monitoring dashboards.
- An administrative web UI.
- Recurring workflow scheduling.
- User-configurable per-step execution timeouts.
- Conditional execution.

Configurable task timeouts are optional, but recognizing and recovering abandoned worker attempts remains required.

Additional capabilities outside the initial scope include multi-language workers, arbitrary command execution, launching containers for individual steps, and visual workflow editing. A separate message broker is an implementation choice rather than a required component. Basic diagnostic logs and useful errors remain part of the core implementation even though advanced observability is optional.

## Overall Plan

The following milestones are proposed for discussion and approval, rather than authorization to begin implementation:

1. **Agree on architecture and execution behavior.** Settle component boundaries, task execution contracts, ownership and recovery behavior, retry semantics, and assumptions. Capture the rationale in `DESIGN.md`.
2. **Build a minimal end-to-end execution.** Establish the Maven project, persistent storage, submission/status APIs, and one worker executing a simple sequential workflow.
3. **Complete DAG execution and distributed coordination.** Add parallel branches and joins, multiple workers, safe task claims, stale-worker protection, and recovery. Verify these behaviors as they are introduced.
4. **Complete failure handling and API coverage.** Finish automatic retries, backoff, exhausted-retry failure, manual retry, listing, and execution history. Extend tests for failure and restart scenarios.
5. **Verify and package the submission.** Complete Docker Compose, sample workflows, reproducible failure demonstrations, documentation, and fresh-clone verification. Address optional work only if time remains.

Keep documentation current throughout implementation. Follow `AGENTS.md`: work on one approved milestone at a time, report verification results and remaining risks, recommend a checkpoint commit, and wait for approval before beginning the next milestone.

## Required Documentation

- **`README.md`:** build and quick-start instructions, architecture overview with a diagram, example API usage, and testing instructions.
- **`DESIGN.md`:** architecture decisions, alternatives, tradeoffs including consistency and availability during partitions, failure modes, execution guarantees, scalability limits, and prioritized future improvements.

## Completion Criteria

The project is complete when a fresh clone can start the system through Docker Compose, execute and inspect the sample workflows, and reproduce the required concurrency, retry, persistence, and recovery behavior. Tests and documentation must support the claimed behavior and clearly identify assumptions and limitations.

## Sources

- Project engineering instructions: `AGENTS.md`.
- Scope discussions: focus on required capabilities, a small Java execution contract, and reliable distributed behavior before optional features.
