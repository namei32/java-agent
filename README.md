# Namei Agent Java

Namei Agent Java 是 Akashic Agent 的渐进式 Java 重写项目。当前已实现同步 HTTP 被动聊天、会话历史恢复、具备安全预算的只读 Tool Runtime，以及默认关闭的 Context/Memory、静态 stdio MCP 只读客户端和认证 Loopback 控制面后端，并把最终 `user/assistant` 对话轮次原子写入 SQLite。Memory 可选择 R4.1 只读 Markdown Profile，或在 Loopback 上显式启用独立 Java `agent-memory.db`、管理 API、Embedding、cosine/Hotness 检索和临时 Context Frame。启动配置支持原有环境变量模式，以及只读解析 Python `config.toml` 的兼容模式。

项目使用 JDK 21、Maven Wrapper、Spring Boot 4.1、Spring AI 2.0、官方 MCP Java SDK 2.0 和 SQLite。默认仅监听 `127.0.0.1`，不提供远程访问认证。MCP 关闭时 Tool Runtime 只注册无副作用的 `current_time`；经单独批准静态启用后，也只接收本地 Allowlist 的 `READ_ONLY` MCP Tool。运行时具有模式、调用预算、Schema 校验、Arguments/Result/Wire 上限、超时、并发许可和取消协议。审批指纹、整批门禁、幂等 Ledger Port 和 `UNKNOWN` 安全语义已经实现，但生产只装配 Deny All；尚无可用的人类审批渠道、生产 Durable Ledger、真实副作用工具、真实/远程 MCP Server、主动消息或流式响应。

Java 原生语义记忆不读取、迁移或删除 Python `memory2.db`，也不会从普通聊天自动提取记忆。模板和部署默认保持 `AGENT_MEMORY_MODE=DISABLED`；当前实现不包含 Optimizer、Scheduler、Memory Tool、远程 Vector Store 或真实用户数据迁移。

## 模块

- `agent-kernel`：领域模型、历史选择和 Port，只依赖 JDK。
- `agent-application`：聊天与记忆用例、语义排序/注入、失败语义和单进程会话串行控制。
- `adapter-workspace`：固定 Markdown Profile 的严格 UTF-8 只读适配器。
- `adapter-sqlite`：会话与 Java Memory 的显式 SQL、版本化 Schema、Float32 Codec 和事务持久化。
- `adapter-spring-ai`：Chat/Embedding 项目协议与 Spring AI 的边界适配。
- `adapter-mcp`：官方 MCP Client 与项目 Tool 之间的静态只读适配、有界 stdio、取消和进程生命周期。
- `agent-bootstrap`：Spring Boot 启动、Chat/Memory HTTP、配置、安全门禁、健康检查和安全日志。

## 本地要求

- JDK 21，禁止使用其他主要版本或预览特性。
- macOS、Linux 或可运行 Maven Wrapper 的等价环境。
- 一个 Java 专用工作区；禁止与正在运行的 Python Agent 共用可写工作区。
- 一个 OpenAI-compatible Chat Completions 服务及其 Base URL、API Key 和模型名。

无需安装系统 Maven，所有命令都使用仓库中的 `./mvnw`。

## 快速开始

```bash
cp .env.example .env
set -a && source .env && set +a
./mvnw clean verify
java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar
```

`.env.example` 中的值只是模板。使用 OpenAI 官方 API 时，`OPENAI_BASE_URL` 应包含 `/v1`；其他兼容服务按其文档填写对应的 API 根路径。

## 配置模式

未指定 TOML，且项目根目录不存在 `config.toml` 时，应用使用环境变量模式：

- `OPENAI_BASE_URL`：OpenAI-compatible API 根路径。
- `OPENAI_API_KEY`：模型服务密钥。
- `OPENAI_MODEL`：模型名。
- `AKASHIC_WORKSPACE`：Java 专用工作区，默认 `./workspace`。
- `AGENT_MEMORY_MODE`：支持 `DISABLED`、`READ_ONLY` 和 `JAVA_NATIVE`，模板与部署默认 `DISABLED`。`READ_ONLY` 只读取 `memory/SELF.md`、`memory/MEMORY.md`、`memory/RECENT_CONTEXT.md`；`JAVA_NATIVE` 不读取旧 Markdown/Python 记忆，而是在 Loopback 上启用独立 Java 语义库、管理 API 和 Retrieval。
- `AGENT_MEMORY_MAX_FILE_BYTES`：单个 Profile 文件 UTF-8 字节上限，默认 `65536`。
- `AGENT_MEMORY_MAX_CONTEXT_CHARACTERS`/`AGENT_MEMORY_MAX_RETRIEVED_CHARACTERS`：全部 Profile Section 与单个检索块字符上限，默认 `100000`/`20000`。
- `AGENT_MEMORY_EMBEDDING_MODEL`/`AGENT_MEMORY_EMBEDDING_DIMENSIONS`/`AGENT_MEMORY_EMBEDDING_MAX_TEXT_CODE_POINTS`：Java Memory 的逻辑 Embedding 模型、维度和单条输入 Code Point 上限，默认 `text-embedding-v3`/`1024`/`2000`。
- `AGENT_MEMORY_RETRIEVAL_TOP_K`/`AGENT_MEMORY_RETRIEVAL_SCORE_THRESHOLD`：召回上限和 cosine 基础阈值，默认 `8`/`0.45`。
- `AGENT_MEMORY_RETRIEVAL_HOTNESS_ALPHA`/`AGENT_MEMORY_RETRIEVAL_HOTNESS_HALF_LIFE_DAYS`：Hotness 混合权重和基础半衰期，默认 `0.20`/`14`。
- `AGENT_MEMORY_RETRIEVAL_MAX_CANDIDATES`/`AGENT_MEMORY_RETRIEVAL_MAX_INJECTED_CHARACTERS`：候选硬上限和注入字符预算，默认 `10000`/`6000`；注入预算不得超过外层检索预算。
- `AGENT_MCP_MODE`：支持 `DISABLED` 和 `STATIC_READ_ONLY`，模板与部署默认 `DISABLED`。关闭时零 MCP 配置读取、零 Client、零子进程、零 MCP Tool；静态启用需要同时启用全局 Tool Runtime，并使用单独批准的绝对配置路径、stdio Executable 和只读 Allowlist。
- `AGENT_MCP_CONFIG_FILE`：版本化严格 JSON 配置的绝对路径；只保存命令参数、环境变量名和只读 Tool Policy，禁止保存 Secret 值。
- `AGENT_MCP_*` 其余字段：限制 Server/Tool/分页数量、连接/请求/关闭超时、Schema/Wire 字节和单 Server 并发；完整范围见运行手册。
- `AGENT_CONTROL_PLANE_MODE`：支持 `DISABLED` 和 `LOOPBACK`，模板与部署默认 `DISABLED`。显式启用时仍要求 `server.address` 为 Loopback，只创建进程内 Bearer Session，并仅管理当前存活的 Telegram Turn；不开放远程访问、CLI+Web、同步 Chat 取消或历史重放。
- `AGENT_CONTROL_PLANE_*` 其余字段：限制 Session TTL/数量、活动 Turn/Tombstone、SSE Subscriber/队列、Heartbeat、流生命期和共享关闭时间；完整边界见控制面契约与运行手册。
- `AGENT_TOOL_MAX_ITERATIONS`：单次聊天允许的最大模型调用次数，默认 `6`。
- `AGENT_TOOL_MODE`：支持 `DISABLED`、`READ_ONLY` 和 `APPROVAL_REQUIRED`。模板和部署保持 `DISABLED`；`READ_ONLY` 仍需同一 Provider/模型组合的真实 Tool Smoke 与部署批准。`APPROVAL_REQUIRED` 当前只有生产 Deny All，不提供审批入口或副作用能力。
- `AGENT_TOOL_MAX_CALLS_PER_RESPONSE`/`AGENT_TOOL_MAX_CALLS_PER_TURN`：单响应与单轮 Tool Call 上限，默认 `8`/`16`。
- `AGENT_TOOL_TIMEOUT`：许可等待与单次执行共享的时限，默认 `5s`。
- `AGENT_TOOL_MAX_CONCURRENT_CALLS`：JVM 内跨会话工具执行许可，默认 `32`。
- `AGENT_TOOL_MAX_ARGUMENT_BYTES`/`AGENT_TOOL_MAX_RESULT_CHARACTERS`：参数 UTF-8 字节与结果 Unicode 字符上限，默认 `16384`/`20000`。
- `AGENT_TOOL_APPROVAL_TIMEOUT`：审批请求有效期，默认 `5m`，必须大于零且不超过 `15m`；设置它不会启用 Approval Channel。

如需沿用 Python 风格配置，可从安全示例创建本地文件：

```bash
cp config.example.toml config.toml
```

随后在 `.env` 中删除 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL`，并提供示例引用的密钥：

```dotenv
AKASHIC_WORKSPACE=./workspace-java
DEEPSEEK_API_KEY=replace-me
```

Java 自动发现项目根目录的 `config.toml`。也可以通过 `--agent.config-file=/path/to/config.toml` 或 `NAMEI_CONFIG_FILE` 指定；三者优先级依次为命令行、环境变量、当前目录默认文件。`OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL` 在 TOML 模式下仍是最高优先级覆盖值。

启动前可执行只读检查；它不会启动 Spring、HTTP Server、模型客户端，也不会创建 Workspace 或 SQLite：

```bash
set -a && source .env && set +a
java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar \
  --agent.config-check
```

检查成功返回退出码 `0`，配置无效返回 `2`。JSON 只包含模式、配置路径、字段来源、Secret 状态、Deferred/未知路径和诊断码，不包含密钥或 Prompt 原文。完整步骤和 DeepSeek 排障见[本地开发运行手册](docs/runbooks/local-development.md)。

启动后发送请求：

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: local-demo-1' \
  -d '{"sessionId":"demo","message":"你好"}' \
  http://127.0.0.1:8080/api/v1/chat
```

健康检查：

```bash
curl --fail-with-body http://127.0.0.1:8080/actuator/health
```

## 验证命令

```bash
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

三个离线命令现在使用互斥测试集：默认只跑常规回归，`failure` 只跑故障/竞态，`compat` 只跑
Golden/Schema 契约。完整阶段门禁需要依次执行三条命令；单独 Profile 不再重复默认回归。

默认构建和 `compat` Profile 都不会访问真实模型。`real-model-smoke` 会产生真实网络请求和可能的模型费用，只有在明确授权并配置 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL` 后才可运行：

```bash
./mvnw -Preal-model-smoke verify
```

2026-07-14 已对 DeepSeek `deepseek-v4-flash` 完成一次真实 Tool Smoke，覆盖 `current_time` Tool Call、Java 执行、Tool Result 回送、最终文本和 SQLite 最终轮次提交。该证据不自动授权启用部署，也不适用于其他 Provider 或模型；当前模板继续使用 `DISABLED`。

R5.1 的 MCP 验收只使用仓库编译的 Java Reference Server 和 stdio，不运行 Python、真实 MCP Server、真实 Secret 或外部网络。默认、`failure`、`compat` 分别通过 284、63、323 项测试；这是 2026-07-17 互斥测试集生效前的历史口径。`real-model-smoke` 不包含真实 MCP Smoke。

R6.5 控制面后端 G0–G9 已完成本地离线实现和阶段门禁：格式、默认、`failure`、`compat` 均为零失败。该结论只覆盖默认关闭的 Loopback API、进程内 Session、安全状态/取消和 future-only SSE；真实 Telegram、远程访问、CLI+Web 与前端仍被冻结。

## 数据安全

会话数据库位于 `${AKASHIC_WORKSPACE}/sessions.db`。`AGENT_MEMORY_MODE=READ_ONLY` 只约束 Markdown Profile，不会把整个 Java 进程变成只读：聊天仍会写入会话 SQLite。`JAVA_NATIVE` 还会创建 `${AKASHIC_WORKSPACE}/memory/agent-memory.db`，并在写入与非空检索时调用 Embedding Provider。MCP 默认关闭；静态启用时子进程环境先清空再复制明确 Allowlist，配置与日志不得包含 Secret。所有模式都只能使用 Java 专用或测试 Workspace，禁止把 Java 指向真实 Python Workspace；Java 不读取、迁移或删除 `memory2.db`。首次让 Java 写入任何既有 Java 数据前，必须停止相关进程，并完整备份数据库及其 `-wal`/`-shm` 文件。

文档入口：

- [项目文档导航](docs/README.md)
- [Java 重写 Roadmap](docs/roadmap/java-rewrite-roadmap.md)
- [Python/Java 能力差距矩阵](docs/architecture/python-java-capability-matrix.md)
- [Java 重写指南](docs/architecture/java-rewrite-guide.md)
- [本地开发与故障排查](docs/runbooks/local-development.md)
- [被动聊天 HTTP 契约](docs/contracts/passive-chat-http.md)
- [Python/Java Golden Test 夹具规范](docs/contracts/golden-test-fixtures.md)
- [Python/Java 配置兼容契约](docs/contracts/python-java-configuration.md)
- [Tool 审批、副作用、幂等与沙箱安全契约](docs/contracts/tool-approval-side-effect-safety.md)
- [只读上下文与记忆兼容契约](docs/contracts/read-only-context-memory.md)
- [Java 原生语义记忆、持久化与优化器契约](docs/contracts/semantic-memory-persistence-optimizer.md)
- [MCP 只读客户端与 Tool Runtime 契约](docs/contracts/mcp-client-tool-runtime.md)
- [MCP 只读客户端纵向切片工作计划](docs/plans/2026-07-15-mcp-read-only-client-implementation.md)
- [R6 渠道、消息总线与控制面总体工作计划](docs/plans/2026-07-15-r6-channel-message-control-plane-master-plan.md)
- [Loopback 控制面契约](docs/contracts/loopback-control-plane.md)
- [R6.5 Loopback 控制面后端实施计划](docs/plans/2026-07-17-r6-loopback-control-plane-implementation.md)
- [MVP 设计](docs/specs/2026-07-12-passive-chat-mvp-design.md)
- [轻量 Vibe Coding 工作流](docs/vibe-coding-workflow.md)
