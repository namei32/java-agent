# 被动聊天 MVP 设计

- 状态：已实现并验证
- 日期：2026-07-12
- 最后更新：2026-07-13
- 项目：Namei Agent Java 重写
- 父工程：`io.namei.agent:namei-agent-parent:0.1.0-SNAPSHOT`

## 1. 目的

本文档定义 Akashic Agent Java 重写的第一个可执行垂直切片：通过 HTTP 暴露的被动同步聊天闭环。

当前以 Python 实现作为行为基准。Java 应用必须保持本文档声明为兼容范围内的可观察行为和数据约定。迁移采用渐进方式；本里程碑不会在生产环境中替换 Python 应用。

## 2. 范围

MVP 实现以下流程：

```text
接收文本消息
  -> 校验请求
  -> 读取或创建 Session
  -> 组装 Prompt 和历史消息
  -> 调用 OpenAI-compatible 模型
  -> 原子持久化 user 和 assistant 消息
  -> 返回 assistant 响应
```

包含：

- 同步 Spring MVC API。
- 项目自有的领域对象、应用服务和端口。
- Spring AI 模型适配器和使用显式 SQL 的 SQLite 适配器。
- 同一会话 ID 请求的进程内顺序保证。
- 确定性的历史消息选择和结构化错误响应。
- 离线自动化测试，以及按需启用的兼容性和真实模型 Maven Profile。
- 面向本地单实例服务的安全默认值。

不包含：

- Tool Loop、MCP、插件、记忆检索、记忆整合和 Akasha 记忆。
- 主动消息、Drift、子智能体和后台任务。
- Channel、Dashboard 修改、流式响应和 SSE。
- 运行时模型提供方切换、故障转移、多模态输入、附件、Tool Call、Token 统计和复杂消息元数据。
- 分布式顺序保证、远程部署、认证和多租户。
- Flyway、JPA、Hibernate ORM、R2DBC、WebFlux、Kafka、Redis、GraalVM、Docker 镜像和 Maven Central 发布。

## 3. 架构

MVP 采用 Ports and Adapters 风格的模块化单体。智能体运行时由项目自行控制；Spring 和 Spring AI 是可替换的边缘技术。

```text
agent-bootstrap
    |-- agent-application
    |-- adapter-spring-ai
    `-- adapter-sqlite

agent-application --> agent-kernel
adapter-spring-ai --> agent-kernel
adapter-sqlite --> agent-kernel
agent-kernel --> 仅依赖 JDK 21
```

### 3.1 模块职责

`agent-kernel`：

- 定义 `Conversation`、`ChatMessage`、`MessageRole` 和领域不变量。
- 定义 `ChatModelPort` 和 `SessionRepository`。
- 负责按完整对话轮次选择历史消息。
- 不依赖 Spring、Spring AI、JDBC、Reactor 或模型提供方 SDK。

`agent-application`：

- 提供 `ChatUseCase`。
- 编排历史读取、模型输入构造、模型调用和原子持久化。
- 提供进程内会话执行闸门。
- 不感知 HTTP、Spring AI、SQLite 或模型提供方类型。

`adapter-spring-ai`：

- 实现 `ChatModelPort`。
- 在项目自有消息类型和 Spring AI 对象之间转换。
- 将上游超时、调用失败、响应格式错误和空响应转换为项目自有异常。

`adapter-sqlite`：

- 使用 JDBC 和显式 SQL 实现 `SessionRepository`。
- 负责 Schema 初始化、兼容性检查、事务、序号分配和数据库行映射。
- 不包含应用编排逻辑。

`agent-bootstrap`：

- 是唯一可执行的 Spring Boot 模块。
- 提供 HTTP DTO、校验、异常映射、配置绑定、依赖装配和启动入口。
- 不包含智能体业务规则。

本里程碑不引入 `module-info.java`。

## 4. Maven 坐标与目录

```text
groupId:    io.namei.agent
artifactId: namei-agent-parent
version:    0.1.0-SNAPSHOT
packaging:  pom
```

基础包名：

```text
io.namei.agent.kernel
io.namei.agent.application
io.namei.agent.adapter.springai
io.namei.agent.adapter.sqlite
io.namei.agent.bootstrap
```

仓库目录：

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
|   |-- specs/
|   `-- vibe-coding-workflow.md
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

请求：

```json
{
  "sessionId": "demo-session",
  "message": "你好"
}
```

成功响应：

```json
{
  "sessionId": "demo-session",
  "message": {
    "role": "assistant",
    "content": "你好，有什么可以帮你？"
  }
}
```

校验规则：

- `sessionId` 必填，长度为 1–128 个字符，只允许 ASCII 字母、数字、`-` 和 `_`。
- `message` 必填，去除首尾空白后长度为 1–32,000 个字符。
- 模型调用和持久化均使用规范化后的消息。
- 客户端不能指定消息角色、System Prompt、模型提供方、模型、Temperature、工作区或数据库路径。
- HTTP DTO 必须转换为项目自有的命令和结果对象。
- 响应不得暴露 Spring AI 类型、SQLite 行 ID 或模型提供方原始载荷。

错误使用 RFC 9457 `ProblemDetail`，不得包含堆栈信息、密钥、Prompt、消息正文或模型提供方原始响应体：

- `400`：JSON 格式错误或字段校验失败。
- `413`：HTTP 请求体超过 65,536 字节。
- `502`：模型提供方调用失败、响应格式错误或返回空响应。
- `504`：模型调用超时或会话锁等待超时。
- `500`：SQLite 错误或未分类的内部错误。

每个响应都必须包含 `X-Request-Id`。

## 6. 应用流程与并发

```text
校验请求
  -> 获取会话执行许可
  -> 读取已持久化的历史消息
  -> 选择最近的完整对话轮次
  -> 组装 System Prompt、历史消息和当前 user 消息
  -> 调用 ChatModelPort
  -> 校验 assistant 响应
  -> 在一个事务中持久化 user 和 assistant 消息
  -> 返回响应
  -> 释放执行许可
```

当前 user 消息在模型成功前只保存在内存中。模型调用或持久化失败时，本对话轮次不得留下任何消息。

同一 `sessionId` 的请求串行执行，不同会话可以并行执行。该保证仅在单个进程内有效。执行闸门使用按 Key 管理且带引用计数的锁条目；没有持有者或等待者时必须移除对应条目。获取锁时必须遵守请求超时限制。

Spring MVC 使用 JDK 21 虚拟线程执行阻塞式模型 HTTP 调用和 SQLite JDBC 操作。优雅关闭期间拒绝新任务，并在配置的时限内等待正在执行的请求结束。

## 7. 模型适配器

`ChatModelPort` 只接收项目自有消息类型，并返回项目自有的 assistant 响应。生产适配器使用 Spring AI 调用一个已配置的 OpenAI-compatible 模型提供方和模型。

MVP 不支持运行时切换或自动故障转移。测试使用可编程假模型（Fake Model）或本地 HTTP 桩服务；默认构建不得调用真实模型提供方。

适配器必须区分模型提供方拒绝、上游服务错误、超时、非法 JSON、缺失 Choices 或 Content，以及空白 Content。模型提供方特有错误必须先转换为稳定的项目异常，才能进入应用层或 HTTP 层。

## 8. Prompt 与历史消息

组装顺序固定为：

```text
版本化的 System Prompt
  -> 已持久化的 user/assistant 历史消息
  -> 当前尚未持久化的 user 消息
```

System Prompt 存放于：

```text
agent-bootstrap/src/main/resources/prompts/system.md
```

MVP 只提供一个默认 Prompt。工作区覆盖和动态重载延后实现。

历史选择同时受消息数量和字符数量限制。选择过程从最新的完整对话轮次向前进行，禁止包含没有对应 user 消息的孤立 assistant 消息。System Prompt 和当前消息始终保留。MVP 不进行精确 Token 计算、摘要压缩或记忆注入。超长用户输入必须拒绝，不能静默截断。

## 9. 配置与密钥

```yaml
agent:
  workspace: ${AKASHIC_WORKSPACE:./workspace}
  history:
    max-messages: 40
    max-characters: 100000
  model:
    timeout: 60s

spring:
  ai:
    openai:
      base-url: ${OPENAI_BASE_URL} # OpenAI 官方地址为 https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}
      chat:
        model: ${OPENAI_MODEL}
        temperature: 0.7
```

API Key 只能来自环境变量或外部密钥管理机制。仓库可以包含 `.env.example`，但禁止包含 `.env` 或真实凭据。

工作区、Base URL、API Key 或模型名缺失或无效时，应用必须快速启动失败。HTTP 客户端不能覆盖这些配置。日志不得包含 API Key、Authorization Header、完整 Prompt、消息正文或模型提供方原始请求。

## 10. SQLite 兼容性

MVP 使用 Python 当前的核心 Schema：

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

MVP 写入约定：

- `metadata` 和 `extra` 写入 `{}`；`tool_chain` 写入 `NULL`。
- `last_consolidated` 保持 `0`；`last_proactive_at` 保持 `NULL`。
- 写入 user 消息时更新 `last_user_at`。
- Timestamp 使用带 UTC Offset 的 ISO-8601 格式。
- Message ID 使用 `{sessionId}:{seq}`。
- 一个对话轮次中 user 使用序号 `n`，assistant 使用序号 `n + 1`。
- 读取器必须容忍未知列，且不得重写不支持的 JSON 数据。

成功持久化使用一个短事务：

```text
BEGIN
  -> 创建或更新会话
  -> 校验并分配 next_seq
  -> 插入 user
  -> 插入 assistant
  -> 将 next_seq 更新为 n + 2
  -> 更新 updated_at 和 last_user_at
COMMIT
```

任何失败都必须回滚整个对话轮次。默认数据库路径为 `${agent.workspace}/sessions.db`。开发和测试使用 Java 专用工作区。本里程碑禁止写入真实 Python 工作区。兼容性测试只能修改由 Python Schema 生成的测试数据临时副本。

适配器提供显式、幂等的 Schema 初始化器，并使用 `PRAGMA table_info` 检查兼容性。适配器必须设置有限的 `busy_timeout`，不得创建或依赖 FTS 表与触发器。Flyway 延后到第一次经过批准的 Schema 演进时再决定。

## 11. 运行环境与安全边界

服务默认绑定 `127.0.0.1`，禁止默认绑定 `0.0.0.0`。默认关闭 CORS，限制 HTTP 请求体大小，并且禁止把会话 ID 解释为文件路径。

MVP 是仅限本地访问的服务，因此不引入 Spring Security。任何远程访问需求都必须先通过独立 ADR 和规格定义认证、TLS、限流和部署控制。

## 12. 可观测性

结构化事件只能包含：

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

合法的入站 `X-Request-Id` 应原样保留，否则由服务器生成。成功和错误响应必须返回同一个 ID。日志中的会话 ID 只能记录为稳定哈希。

只开放 `/actuator/health`，默认不显示组件详情。健康检查只检查应用和 SQLite 可用性，不得调用 LLM。禁止开放环境变量、Bean、配置、线程和 Heap Dump 等端点。指标导出和 OpenTelemetry 延后实现。

## 13. 构建规则

- Java Compiler 的 `release` 为 21。
- Maven Enforcer 要求使用 JDK 21 和 Maven 3.9 或更高版本。
- Spring Boot 使用已批准的 `4.1.x` 版本线。
- Spring AI 通过 BOM 使用已批准的 `2.0.x` 版本线。
- Maven Wrapper 必须提交到仓库，并作为唯一支持的命令入口。
- Surefire 运行单元测试；Failsafe 运行集成测试。
- JaCoCo 生成覆盖率报告；Spotless 格式化 Java 和 POM 文件。
- ArchUnit 校验包和模块边界。
- 发布构建禁止使用第三方 Snapshot 依赖。
- 只有 `agent-bootstrap` 生成可执行 Spring Boot JAR。
- 子 POM 不得重复声明由父 POM 或 BOM 管理的版本。
- 禁止使用 Lombok、JPA、R2DBC 和 Spring JDBC。
- `adapter-sqlite` 使用 JDBC、SQLite JDBC 和 Jackson。
- `agent-application` 只依赖 `agent-kernel`。

开始创建工程骨架时，应先检查当时可用的稳定版本，并在提交或 PR 中记录准确的补丁版本；所选版本必须位于已批准的次版本线内。

## 14. 测试策略

Kernel 模块测试覆盖领域不变量、消息角色与内容规则，以及完整对话轮次的历史消息选择。

Application 模块测试使用可编程假模型和内存 Fake Repository。在不加载 Spring 的前提下，验证编排顺序、模型失败后零持久化、同会话串行和跨会话并行。

SQLite 适配器测试使用临时目录中的真实 SQLite 文件，禁止使用 H2。测试覆盖 Schema、Message ID、序号分配、事务回滚、重启恢复、Python Schema 测试数据、未知 JSON 和额外列。

Spring AI 适配器测试使用本地 OpenAI-compatible HTTP 桩服务。覆盖成功、空响应、HTTP 401、429、500、超时和非法 JSON，并禁止访问公网。

Bootstrap 模块测试使用 `MockMvc` 验证 HTTP 契约和启动失败行为。使用 ArchUnit 强制执行已批准的架构边界。

命令和 Maven Profile：

```bash
./mvnw test
./mvnw clean verify
./mvnw -Pcompat verify
./mvnw -Preal-model-smoke verify
```

Maven 依赖完成解析后，默认构建不得连接模型提供方，也不需要 API Key。兼容性测试验证 Python 数据约定。真实模型测试必须显式启用，只能从环境读取密钥，并从默认 CI 中排除。

生产行为必须遵循 Red-Green-Refactor：先观察目标测试因预期原因失败，再编写使测试通过所需的最小实现。

## 15. 验收标准

1. 新会话能完成一次聊天，并存储一对 user/assistant 消息。
2. 第二个对话轮次调用模型时，能按规定顺序携带第一个完整对话轮次。
3. 应用重启后能恢复并继续使用历史消息。
4. 模型失败、超时、响应无效或 SQLite 写入失败时，本对话轮次保持零消息写入。
5. 同一会话的并发请求形成互不交错的完整对话轮次。
6. 不同会话的请求可以并行执行。
7. Java 能正确读取已批准的 Python-compatible 测试数据。
8. 输入校验和错误响应符合已批准的 HTTP 契约。
9. `agent-kernel` 不依赖 Spring、JDBC、Reactor 或模型提供方 SDK。
10. 服务默认绑定环回地址，并且只开放已批准的 Actuator 端点。
11. 日志不包含密钥、Prompt 或消息正文。
12. 在不访问模型网络且不提供 API Key 的情况下，`./mvnw clean verify` 能成功执行。

## 16. 交付流程

1. 提交本设计，并由用户复核书面文件。
2. 在当前任务中拆分逐文件、可聚焦验证的实施步骤。
3. 在隔离的功能分支或 Git Worktree 中工作。
4. 使用 TDD 实现每个行为。
5. 对兼容性相关工作进行单独的规格与质量审查。
6. 声称完成前执行全新的目标验证和完整验证。
7. 在最终门禁和整体自审通过后，按用户授权完成本地集成。

书面规格通过用户复核且当前任务边界明确之前，禁止创建生产工程骨架或编写生产代码。

## 17. 实现验证记录

2026-07-13 完成离线实现与最终门禁：

- `./mvnw clean verify`：63/63，通过；默认排除 `compat` 和 `real-model`，未访问真实模型。
- `./mvnw -Pfailure verify`：17/17，通过；只选择 `failure` Tag，Failsafe 集成测试选择为 0。
- `./mvnw -Pcompat verify`：64/64，通过；明确执行 `PythonSchemaCompatibilityIT`，继续排除 `real-model`。
- `./mvnw -pl agent-kernel dependency:tree`：Kernel 仅包含 JUnit 与 AssertJ 测试依赖。
- 禁止依赖、Secret 和 Workspace 文件扫描：零命中。
- 可执行 Boot JAR 使用 Java 21 成功启动，`/actuator/health` 返回 `UP`。

最终整体自审覆盖 Spec 符合度、架构依赖方向、SQLite 原子性与 Python Schema 兼容性、同会话并发语义、日志脱敏和 HTTP 错误状态。审查发现的 `failure` Profile 选择范围和多模块启动命令问题已修正；没有未解决的 Critical 或 Important 问题。

`real-model-smoke` 需要真实网络、密钥且可能产生费用，本次未获单独授权，因此没有运行。
