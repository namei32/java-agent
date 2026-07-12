# AGENTS.md

## Mission

Rewrite Akashic Agent in Java while preserving observable behavior,
workspace compatibility, and existing user data.

The Python implementation is the current behavioral oracle.
This is an incremental rewrite, not a big-bang replacement.

## Fixed Technology Baseline

- Use JDK 21.
- Do not use preview APIs or `--enable-preview`.
- Build with Maven Wrapper: `./mvnw`.
- Use Spring Boot 4.1.x.
- Use Spring AI 2.0.x only as an integration adapter.
- The project owns its agent loop, tool execution, approval flow,
  context management, and session lifecycle.

## Architecture Boundaries

- `agent-kernel` must not depend on Spring, JDBC, Reactor,
  Spring AI, or provider SDK classes.
- Domain interfaces belong in the kernel.
- External frameworks and SDKs belong in adapter modules.
- Spring AI must not become the owner of the agent runtime.
- SQLite access uses explicit SQL and transactions.
- Do not introduce JPA, R2DBC, WebFlux, Kafka, Redis, GraalVM,
  or distributed infrastructure without an approved ADR.
- Keep the existing React/Vite frontend unchanged during the
  initial rewrite unless the specification explicitly says otherwise.

## Mandatory Development Workflow

Before implementing new behavior:

1. Use `using-superpowers` to select the applicable workflow.
2. Use `brainstorming` to clarify behavior and constraints.
3. Write and approve a specification.
4. Use `writing-plans` to produce an executable implementation plan.
5. Create an isolated feature branch or Git worktree.
6. Implement with test-driven development.
7. Request independent code review.
8. Run fresh verification before claiming completion.
9. Use `finishing-a-development-branch` before integration.

Do not write production code before the specification and plan exist.

For bugs:

1. Reproduce the problem with a failing test.
2. Use `systematic-debugging` to identify the root cause.
3. Make the smallest justified fix.
4. Run regression and full verification.

## Test-Driven Development

Follow red-green-refactor:

1. Add a test for one observable behavior.
2. Run it and confirm that it fails for the expected reason.
3. Implement the minimum code required.
4. Run the test and confirm that it passes.
5. Refactor only while tests remain green.

A test written after the implementation is not considered TDD evidence.

## Build and Verification

Primary commands:

```bash
./mvnw clean verify
./mvnw -pl <module> -am test
./mvnw -Pcompat verify
./mvnw -Pfailure verify
```

Before claiming completion:

- Run the relevant targeted tests.
- Run `./mvnw clean verify`.
- Run compatibility tests when observable behavior changes.
- Run failure-injection tests when persistence, tools, approval,
  cancellation, or concurrency behavior changes.
- Report the exact commands and results.
- Never claim success from old or partial test output.

## Compatibility and Data Safety

- Treat the Python implementation as the behavioral oracle until
  the corresponding Java behavior has been accepted.
- Never let Python and Java write the same workspace concurrently.
- Access real user workspaces read-only during the initial phases.
- Back up data before the first Java write or schema migration.
- Preserve unknown JSON and TOML fields where possible.
- Do not silently rewrite Markdown memory files.
- Any intentional golden-file or contract change requires review.

Never commit:

- Real `config.toml` files
- API keys or tokens
- Production SQLite databases
- User workspaces
- User memories
- Runtime logs containing private data

## Git Rules

- Do not implement directly on `main`.
- Use short-lived feature branches or worktrees.
- Preserve existing user changes.
- Do not use destructive Git commands without explicit approval.
- Keep commits small and independently verifiable.
- Prefer one observable behavior per commit.
- Do not mix unrelated refactoring with behavior migration.

## Definition of Done

A task is complete only when:

- The specification is approved.
- The implementation plan has been followed or updated.
- Red-green test evidence exists.
- Targeted tests pass.
- `./mvnw clean verify` passes.
- Compatibility differences are explained.
- Review has no unresolved Critical or Important findings.
- Relevant documentation and ADRs are updated.
- No secrets or user data were added.
- Git status contains no unintended changes.

## Documentation Routing

Read the relevant documents before implementation:

- `docs/architecture/java-rewrite-guide.md`
- `docs/adr/`
- `docs/contracts/`
- `docs/superpowers/specs/`
- `docs/superpowers/plans/`
- `docs/runbooks/`

Detailed design decisions belong in these documents, not in this file.
