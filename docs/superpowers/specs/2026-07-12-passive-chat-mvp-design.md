# Passive Chat MVP Design

- Status: Design approved; written review pending
- Date: 2026-07-12
- Project: Namei Agent Java Rewrite
- Parent: `io.namei.agent:namei-agent-parent:0.1.0-SNAPSHOT`

## 1. Purpose

This specification defines the first executable vertical slice of the Akashic
Agent Java rewrite: a passive synchronous chat loop exposed through HTTP.

The Python implementation remains the behavioral oracle. The Java application
must preserve the observable behavior and data conventions declared compatible
by this specification. Migration is incremental; this milestone does not
replace the Python application in production.

## 2. Scope

The MVP implements:

```text
receive text message
  -> validate request
  -> load or create session
  -> assemble prompt and history
  -> invoke an OpenAI-compatible model
  -> atomically persist the user and assistant messages
  -> return the assistant response
```

Included:

- A synchronous Spring MVC API.
- Project-owned domain, application services, and ports.
- A Spring AI model adapter and an explicit-SQL SQLite adapter.
- In-process ordering for requests sharing a session ID.
- Deterministic history selection and structured error responses.
- Offline tests plus opt-in compatibility and real-model profiles.
- Safe defaults for a local, single-instance service.

Excluded:

- Tool Loop, MCP, plugins, memory retrieval, consolidation, and Akasha memory.
- Proactive messages, Drift, subagents, and background jobs.
- Channels, Dashboard changes, streaming, and SSE.
- Runtime provider switching, failover, multimodal input, attachments, tool
  calls, token accounting, and complex message metadata.
- Distributed ordering, remote deployment, authentication, and multi-tenancy.
- Flyway, JPA, Hibernate, R2DBC, WebFlux, Kafka, Redis, GraalVM, Docker images,
  and Maven Central publication.

## 3. Architecture

The MVP is a modular monolith using ports and adapters. The project owns the
agent runtime; Spring and Spring AI are replaceable edge technologies.

```text
agent-bootstrap
    |-- agent-application
    |-- adapter-spring-ai
    `-- adapter-sqlite

agent-application --> agent-kernel
adapter-spring-ai --> agent-kernel
adapter-sqlite --> agent-kernel
agent-kernel --> JDK 21 only
```

### 3.1 Module responsibilities

`agent-kernel`:

- Defines `Conversation`, `ChatMessage`, `MessageRole`, and domain invariants.
- Defines `ChatModelPort` and `SessionRepository`.
- Owns complete-turn history selection.
- Has no Spring, Spring AI, JDBC, Reactor, or provider SDK dependency.

`agent-application`:

- Provides `ChatUseCase`.
- Coordinates history loading, model input, invocation, and atomic persistence.
- Provides the in-process session execution gate.
- Does not know HTTP, Spring AI, SQLite, or provider types.

`adapter-spring-ai`:

- Implements `ChatModelPort`.
- Converts project messages to and from Spring AI objects.
- Converts upstream timeout, failure, malformed response, and empty response to
  project-owned exceptions.

`adapter-sqlite`:

- Implements `SessionRepository` with JDBC and explicit SQL.
- Owns schema initialization, compatibility checks, transactions, sequence
  allocation, and row mapping.
- Contains no application orchestration.

`agent-bootstrap`:

- Is the only executable Spring Boot module.
- Provides HTTP DTOs, validation, exception mapping, configuration binding,
  dependency assembly, and startup.
- Contains no agent business rules.

No `module-info.java` is introduced in this milestone.

## 4. Maven Coordinates and Layout

```text
groupId:    io.namei.agent
artifactId: namei-agent-parent
version:    0.1.0-SNAPSHOT
packaging:  pom
```

Base packages:

```text
io.namei.agent.kernel
io.namei.agent.application
io.namei.agent.adapter.springai
io.namei.agent.adapter.sqlite
io.namei.agent.bootstrap
```

Repository layout:

```text
java-agent/
|-- AGENTS.md
|-- README.md
|-- pom.xml
|-- mvnw
|-- mvnw.cmd
|-- .mvn/
|-- docs/
|   |-- architecture/
|   |-- adr/
|   |-- contracts/
|   |-- runbooks/
|   `-- superpowers/
|       |-- specs/
|       `-- plans/
|-- agent-kernel/
|-- agent-application/
|-- adapter-spring-ai/
|-- adapter-sqlite/
`-- agent-bootstrap/
```

## 5. HTTP Contract

```http
POST /api/v1/chat
Content-Type: application/json
```

Request:

```json
{
  "sessionId": "demo-session",
  "message": "你好"
}
```

Success:

```json
{
  "sessionId": "demo-session",
  "message": {
    "role": "assistant",
    "content": "你好，有什么可以帮你？"
  }
}
```

Validation:

- `sessionId` is required, is 1 through 128 characters, and contains only ASCII
  letters, digits, `-`, and `_`.
- `message` is required and, after trimming, is 1 through 32,000 characters.
- The normalized message is used for the model call and persistence.
- Clients cannot supply role, system prompt, provider, model, temperature,
  workspace, or database path.
- HTTP DTOs are converted to project-owned command and result objects.
- Responses expose no Spring AI type, SQLite row ID, or raw provider payload.

Errors use RFC 9457 `ProblemDetail` without stack traces, secrets, prompts,
message content, or raw provider bodies:

- `400`: malformed JSON or validation failure.
- `502`: provider failure, malformed provider response, or empty response.
- `504`: model timeout or session-lock timeout.
- `500`: SQLite or unclassified internal failure.

Every response includes `X-Request-Id`.

## 6. Application Flow and Concurrency

```text
validate request
  -> obtain session execution permit
  -> load persisted history
  -> select complete recent turns
  -> assemble system prompt, history, and current user message
  -> invoke ChatModelPort
  -> validate assistant response
  -> persist user and assistant in one transaction
  -> return response
  -> release permit
```

The current user message remains in memory until the model succeeds. Model or
persistence failure leaves no messages from the failed turn.

Requests for one `sessionId` execute serially; different sessions may execute
concurrently. This guarantee is process-local. The execution gate uses keyed,
reference-counted lock entries and removes entries with no owner or waiter. Lock
acquisition respects the request timeout.

Spring MVC uses JDK 21 virtual threads for blocking model HTTP and SQLite JDBC
work. Graceful shutdown rejects new work and waits for active requests within a
configured limit.

## 7. Model Adapter

`ChatModelPort` accepts project-owned messages and returns a project-owned
assistant response. The production adapter uses Spring AI with one configured
OpenAI-compatible provider and model.

The MVP has no runtime switching or failover. Tests use a programmable fake
model or local HTTP stub; default builds never call a real provider.

The adapter distinguishes provider rejection, server failure, timeout, invalid
JSON, missing choices or content, and blank content. Provider-specific errors
become stable project exceptions before reaching application or HTTP layers.

## 8. Prompt and History

Assembly order is fixed:

```text
versioned system prompt
  -> persisted user/assistant history
  -> current unpersisted user message
```

The system prompt is stored at:

```text
agent-bootstrap/src/main/resources/prompts/system.md
```

The MVP has one default prompt. Workspace overrides and dynamic reload are
deferred.

History selection obeys both message-count and character-count limits. It walks
backward from the newest complete turn and never includes an assistant message
without its user message. The system prompt and current message are always
included. The MVP has no exact token counting, summary compression, or memory
injection. Oversized user input is rejected rather than truncated.

## 9. Configuration and Secrets

```yaml
agent:
  workspace: ${AKASHIC_WORKSPACE:./workspace}
  system-prompt-resource: classpath:/prompts/system.md
  history:
    max-messages: 40
    max-characters: 100000
  model:
    timeout: 60s

spring:
  ai:
    openai:
      base-url: ${OPENAI_BASE_URL}
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: ${OPENAI_MODEL}
          temperature: 0.7
```

The API key comes only from the environment or an external secret mechanism.
The repository may contain `.env.example`, never `.env` or real credentials.
Startup fails fast for an invalid or missing workspace, base URL, API key, or
model. HTTP clients cannot override these settings. Logs never include API keys,
authorization headers, full prompts, message content, or raw provider requests.

## 10. SQLite Compatibility

The MVP uses the Python core schema:

```sql
CREATE TABLE sessions (
    key                TEXT PRIMARY KEY,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL,
    last_consolidated  INTEGER NOT NULL DEFAULT 0,
    metadata           TEXT,
    last_user_at       TEXT,
    last_proactive_at  TEXT,
    next_seq           INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE messages (
    id           TEXT PRIMARY KEY,
    session_key  TEXT NOT NULL,
    seq          INTEGER NOT NULL,
    role         TEXT NOT NULL,
    content      TEXT,
    tool_chain   TEXT,
    extra        TEXT,
    ts           TEXT NOT NULL,
    UNIQUE (session_key, seq)
);
```

MVP write conventions:

- `metadata` and `extra` are `{}`; `tool_chain` is `NULL`.
- `last_consolidated` remains `0`; `last_proactive_at` remains `NULL`.
- A user write updates `last_user_at`.
- Timestamps are ISO-8601 with UTC offset.
- Message IDs are `{sessionId}:{seq}`.
- A turn uses user sequence `n` and assistant sequence `n + 1`.
- Readers tolerate unknown columns and do not rewrite unsupported JSON data.

Successful persistence is one short transaction:

```text
BEGIN
  -> create or update session
  -> validate and allocate next_seq
  -> insert user
  -> insert assistant
  -> set next_seq to n + 2
  -> update updated_at and last_user_at
COMMIT
```

Any failure rolls back the turn. The default path is
`${agent.workspace}/sessions.db`. Development and tests use a Java-specific
workspace. This milestone never writes a real Python workspace. Compatibility
tests may modify only a temporary copy of a Python-schema fixture.

The adapter has an explicit idempotent initializer and checks
`PRAGMA table_info`. It configures a finite `busy_timeout`. It neither creates
nor requires FTS tables or triggers. Flyway is deferred until the first approved
schema evolution.

## 11. Runtime and Security

The service binds to `127.0.0.1` by default, never `0.0.0.0`. CORS is disabled,
request-body size is bounded, and a session ID is never a path.

Spring Security is excluded because the MVP is local-only. Remote access first
requires a separate ADR and specification covering authentication, TLS, rate
limits, and deployment controls.

## 12. Observability

Structured events contain only:

```text
requestId
sessionIdHash
historyMessageCount
model
modelLatencyMs
databaseLatencyMs
totalLatencyMs
outcome
errorCode
```

A valid incoming `X-Request-Id` is preserved; otherwise one is generated. The
same ID is returned in success and error responses. Session IDs are logged only
as stable hashes.

Only `/actuator/health` is exposed, without component details by default. It
checks application and SQLite availability but never calls the LLM. Environment,
bean, configuration, thread, and heap-dump endpoints are not exposed. Metrics
export and OpenTelemetry are deferred.

## 13. Build Rules

- Java compiler release is 21.
- Maven Enforcer requires JDK 21 and Maven 3.9 or newer.
- Spring Boot uses the approved `4.1.x` line.
- Spring AI uses the approved `2.0.x` line through its BOM.
- Maven Wrapper is committed and is the only supported command entry.
- Surefire runs unit tests; Failsafe runs integration tests.
- JaCoCo produces coverage reports; Spotless formats Java and POM files.
- ArchUnit verifies package and module boundaries.
- Third-party snapshots are prohibited from release builds.
- Only `agent-bootstrap` creates an executable Spring Boot JAR.
- Child POMs do not repeat parent- or BOM-managed versions.
- Lombok, JPA, R2DBC, and Spring JDBC are not used.
- `adapter-sqlite` uses JDBC, SQLite JDBC, and Jackson.
- `agent-application` depends only on `agent-kernel`.

Exact patch versions are selected and recorded in the implementation plan after
checking stable releases at scaffolding time. They must remain inside the
approved minor-version lines.

## 14. Test Strategy

Kernel tests cover domain invariants, role and content rules, and complete-turn
history selection.

Application tests use a programmable fake model and in-memory fake repository.
They verify orchestration, zero persistence after model failure, same-session
serialization, and cross-session concurrency without loading Spring.

SQLite tests use real temporary SQLite files, never H2. They cover schema,
message IDs, sequence allocation, rollback, restart recovery, Python-schema
fixtures, unknown JSON, and extra columns.

Spring AI adapter tests use a local OpenAI-compatible HTTP stub. They cover
success, empty response, HTTP 401, 429, and 500, timeout, and invalid JSON, with
public-network access prohibited.

Bootstrap tests use `MockMvc` for HTTP contracts and startup failure. ArchUnit
enforces the approved boundaries.

Commands and profiles:

```bash
./mvnw test
./mvnw clean verify
./mvnw -Pcompat verify
./mvnw -Preal-model-smoke verify
```

After Maven dependencies have been resolved, the default build does not contact
a model provider and needs no API key. Compatibility tests validate Python
conventions. Real-model tests are opt-in, read secrets only from the environment,
and are excluded from default CI.

Production behavior follows red-green-refactor: first observe the target test
failing for the expected reason, then implement the minimum passing change.

## 15. Acceptance Criteria

1. A new session completes a chat and stores one user/assistant pair.
2. A second turn supplies the first complete turn to the model in order.
3. History is restored and used after application restart.
4. Model failure, timeout, invalid output, and SQLite failure leave zero messages
   from the failed turn.
5. Concurrent requests for one session form non-interleaved complete turns.
6. Different sessions can execute concurrently.
7. Java reads the approved Python-compatible fixture correctly.
8. Validation and errors conform to the approved HTTP contract.
9. `agent-kernel` has no Spring, JDBC, Reactor, or provider SDK dependency.
10. The service defaults to loopback and exposes only the approved Actuator
    endpoint.
11. Logs contain no secrets, prompts, or message content.
12. `./mvnw clean verify` succeeds without model-network access or an API key.

## 16. Delivery Workflow

1. Commit this approved design and obtain user review of the written file.
2. Use `writing-plans` to create a file-by-file implementation plan.
3. Work on an isolated feature branch or Git worktree.
4. Implement each behavior with TDD.
5. Obtain independent specification and quality review for compatibility work.
6. Run fresh targeted and full verification before completion claims.
7. Integrate through `finishing-a-development-branch`.

Production scaffolding and code must not begin until the written specification
passes user review and the implementation plan exists.
