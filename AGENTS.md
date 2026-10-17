# Engineering Instructions

## Project
Build a reliable distributed DAG workflow engine using Java, Maven, Spring Boot, PostgreSQL, and Docker Compose. Follow `PROJECT_CONTEXT.md` and the approved architecture. Prioritize correctness, simplicity, and maintainability within the assignment's time constraints.

## Engineering Principles
- Prefer simple, focused implementations; avoid unnecessary abstractions, dependencies, and premature optimization.
- Separate workflow orchestration, persistence, worker coordination, and business step handlers.
- Use idiomatic Java, clear naming, and appropriate error handling.
- Preserve existing behavior and API contracts unless changes are explicitly approved.
- Do not silently change architectural decisions or modify unrelated components.

## Distributed Systems Invariants
- Execute steps only after all dependencies complete; allow independent steps to run concurrently.
- Ensure only one worker holds a valid claim for a step at a time, using atomic database operations.
- Use leases and fencing to prevent stale workers from overwriting newer execution state.
- Persist workflow state, step results, and execution history across restarts.
- Enforce retry limits and backoff; handle worker crashes and expired leases.
- Mark workflows COMPLETED only when all required steps complete; mark FAILED when retries are exhausted.
- Document at-least-once execution semantics, idempotency assumptions, and known limitations.

## Development and Testing
- Before changes, inspect relevant code and tests, check `git status`, and identify potential regression risks.
- Implement one approved milestone and one logical change at a time; prefer minimal diffs.
- Add or update tests for new behavior, edge cases, and affected distributed-systems invariants.
- Never delete, weaken, or bypass tests merely to make the build pass.
- Run focused tests after changes and the full Maven test suite at milestone boundaries.
- Investigate regressions before declaring completion. Report actual test results, failures, and unverified behavior.
- Stop and request approval if a fix requires architectural changes or substantial scope expansion.

## Git and Change Safety
- Never discard existing changes or run destructive Git commands without explicit approval.
- Do not commit, push, rebase, or rewrite Git history without approval.
- Recommend a checkpoint commit after each verified milestone.
- Summarize changes, test results, and remaining risks before proceeding to the next milestone.
- Do not automatically begin the next milestone.

## Delivery
- Ensure the project builds with Maven and starts using `docker-compose up`.
- Provide reproducible tests and sample workflows demonstrating sequential execution, parallelism, retries, and recovery.
- Keep `README.md` and `DESIGN.md` accurate, including architecture decisions, trade-offs, failure modes, and limitations.