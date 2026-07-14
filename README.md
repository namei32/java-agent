# Namei Agent Java

Namei Agent Java 是 Akashic Agent 的渐进式 Java 重写项目。当前已实现同步 HTTP 被动聊天、会话历史恢复、具备安全预算的只读 Tool Runtime，并把最终 `user/assistant` 对话轮次原子写入 SQLite。启动配置支持原有环境变量模式，以及只读解析 Python `config.toml` 的兼容模式。

项目使用 JDK 21、Maven Wrapper、Spring Boot 4.1、Spring AI 2.0 和 SQLite。默认仅监听 `127.0.0.1`，不提供远程访问认证。当前 Tool Runtime 只注册无副作用的 `current_time`，具有模式、调用预算、Schema 校验、Arguments/Result 上限、超时、并发许可和取消协议。审批指纹、整批门禁、幂等 Ledger Port 和 `UNKNOWN` 安全语义已经实现，但生产只装配 Deny All；尚无可用的人类审批渠道、生产 Durable Ledger、真实副作用工具、MCP、主动消息或流式响应。

## 模块

- `agent-kernel`：领域模型、历史选择和 Port，只依赖 JDK。
- `agent-application`：聊天用例、失败语义和单进程会话串行控制。
- `adapter-sqlite`：显式 SQL、Schema 兼容检查和原子轮次持久化。
- `adapter-spring-ai`：项目模型类型与 Spring AI 的协议适配。
- `agent-bootstrap`：Spring Boot 启动、HTTP、配置、健康检查和安全日志。

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

默认构建和 `compat` Profile 都不会访问真实模型。`real-model-smoke` 会产生真实网络请求和可能的模型费用，只有在明确授权并配置 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL` 后才可运行：

```bash
./mvnw -Preal-model-smoke verify
```

2026-07-14 已对 DeepSeek `deepseek-v4-flash` 完成一次真实 Tool Smoke，覆盖 `current_time` Tool Call、Java 执行、Tool Result 回送、最终文本和 SQLite 最终轮次提交。该证据不自动授权启用部署，也不适用于其他 Provider 或模型；当前模板继续使用 `DISABLED`。

## 数据安全

默认数据库位于 `${AKASHIC_WORKSPACE}/sessions.db`。首次让 Java 写入任何既有数据前，必须停止 Python 和 Java 进程，并一起备份 `sessions.db`、`sessions.db-wal`、`sessions.db-shm`。本里程碑不得写入真实 Python 工作区。

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
- [MVP 设计](docs/specs/2026-07-12-passive-chat-mvp-design.md)
- [轻量 Vibe Coding 工作流](docs/vibe-coding-workflow.md)
