# Namei Agent Java

Namei Agent Java 是 Akashic Agent 的渐进式 Java 重写项目。当前已实现第一个垂直切片：通过同步 HTTP API 完成被动聊天、恢复会话历史，并把完整的 `user/assistant` 对话轮次原子写入 SQLite。

项目使用 JDK 21、Maven Wrapper、Spring Boot 4.1、Spring AI 2.0 和 SQLite。默认仅监听 `127.0.0.1`，不提供远程访问认证，也不包含 Tool Loop、MCP、主动消息或流式响应。

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
- [MVP 设计](docs/specs/2026-07-12-passive-chat-mvp-design.md)
- [轻量 Vibe Coding 工作流](docs/vibe-coding-workflow.md)
