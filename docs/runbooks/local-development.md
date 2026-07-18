# 本地开发与故障排查

本手册中依赖项目文件的命令块会先通过 Git 动态切换到项目根目录。因此，即使终端当前位于 `docs/runbooks` 或仓库内其他子目录，也可以独立执行任意一个完整命令块。命令必须从本 Git 仓库内部启动；如果当前目录不属于该仓库，`git rev-parse --show-toplevel` 会明确失败。

## 1. 环境准备

确认 Java 版本：

```bash
cd "$(git rev-parse --show-toplevel)"
java -version
./mvnw -version
```

两条命令都必须显示 JDK 21；Maven 必须由 `./mvnw` 启动。

项目支持环境变量模式和只读 TOML 兼容模式。两种模式都由 Shell 或部署平台提供进程环境；Java 不自动读取 `.env`。

### 1.1 环境变量模式

创建本地环境文件：

```bash
cd "$(git rev-parse --show-toplevel)"
cp .env.example .env
```

编辑项目根目录的 `.env`：

```dotenv
AKASHIC_WORKSPACE=./workspace
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_KEY=replace-me
OPENAI_MODEL=gpt-4o-mini
AGENT_MODEL_STREAM_IDLE_TIMEOUT=30s
AGENT_MODEL_MAX_DELTA_EVENTS=2048
AGENT_MODEL_MAX_DELTA_CODE_POINTS=32000
AGENT_CLI_SESSION_ID=cli:local
AGENT_CLI_CONVERSATION_ID=local
AGENT_CLI_BUFFER_CAPACITY=32
AGENT_CLI_PUBLISH_TIMEOUT=2s
AGENT_CLI_POLL_TIMEOUT=100ms
AGENT_TELEGRAM_ENABLED=false
AGENT_TELEGRAM_ALLOW_FROM=
AGENT_TELEGRAM_BOT_TOKEN=
AGENT_TELEGRAM_MAX_CONCURRENT_TURNS=8
AGENT_TELEGRAM_BUFFER_CAPACITY=32
AGENT_TELEGRAM_LONG_POLL_TIMEOUT=20s
AGENT_TELEGRAM_POLL_REQUEST_TIMEOUT=25s
AGENT_TELEGRAM_SHUTDOWN_TIMEOUT=5s
AGENT_MEMORY_MODE=DISABLED
AGENT_MEMORY_MAX_FILE_BYTES=65536
AGENT_MEMORY_MAX_CONTEXT_CHARACTERS=100000
AGENT_MEMORY_MAX_RETRIEVED_CHARACTERS=20000
AGENT_MEMORY_EMBEDDING_MODEL=text-embedding-v3
AGENT_MEMORY_EMBEDDING_DIMENSIONS=1024
AGENT_MEMORY_EMBEDDING_MAX_TEXT_CODE_POINTS=2000
AGENT_MEMORY_RETRIEVAL_TOP_K=8
AGENT_MEMORY_RETRIEVAL_SCORE_THRESHOLD=0.45
AGENT_MEMORY_RETRIEVAL_HOTNESS_ALPHA=0.20
AGENT_MEMORY_RETRIEVAL_HOTNESS_HALF_LIFE_DAYS=14
AGENT_MEMORY_RETRIEVAL_MAX_CANDIDATES=10000
AGENT_MEMORY_RETRIEVAL_MAX_INJECTED_CHARACTERS=6000
AGENT_MCP_MODE=DISABLED
AGENT_MCP_CONFIG_FILE=
AGENT_MCP_MAX_SERVERS=4
AGENT_MCP_MAX_TOOLS_PER_SERVER=32
AGENT_MCP_MAX_LIST_PAGES=8
AGENT_MCP_CONNECT_TIMEOUT=5s
AGENT_MCP_REQUEST_TIMEOUT=4s
AGENT_MCP_SHUTDOWN_TIMEOUT=2s
AGENT_MCP_MAX_SCHEMA_BYTES=65536
AGENT_MCP_MAX_WIRE_BYTES=1048576
AGENT_MCP_MAX_CONCURRENT_CALLS_PER_SERVER=1
AGENT_TOOL_MAX_ITERATIONS=6
AGENT_TOOL_MODE=DISABLED
AGENT_TOOL_MAX_CALLS_PER_RESPONSE=8
AGENT_TOOL_MAX_CALLS_PER_TURN=16
AGENT_TOOL_TIMEOUT=5s
AGENT_TOOL_MAX_CONCURRENT_CALLS=32
AGENT_TOOL_MAX_ARGUMENT_BYTES=16384
AGENT_TOOL_MAX_RESULT_CHARACTERS=20000
AGENT_TOOL_APPROVAL_TIMEOUT=5m
```

`OPENAI_BASE_URL` 是 OpenAI-compatible API 根路径。OpenAI 官方地址需要包含 `/v1`。`.env` 已被 Git 忽略，禁止提交真实密钥。

模型流式边界默认允许每 Turn 最多 `2048` 个 Delta 和累计 `32000` 个 Unicode Code Point，Provider 连续 `30s` 没有新 Chunk 会触发空闲超时。三个值都在启动时严格校验；它们限制预览流，不改变最终完整回答作为 SQLite 提交权威快照的语义。

`AGENT_CLI_SESSION_ID` 与 `AGENT_CLI_CONVERSATION_ID` 是受信本地路由，普通输入正文不能覆盖。CLI Buffer 容量必须在 `1..1024`，发布和轮询期限必须大于零且不超过 `30s`。默认值面向单用户本地终端，不表示允许多个终端共享同一 Session 并发写入。

`AGENT_MEMORY_MODE` 默认 `DISABLED`；`READ_ONLY` 只读取固定的三个 Markdown Profile 文件，Retrieval 为空，不启用记忆写入或 Embedding。`JAVA_NATIVE` 不读取旧 Markdown/Python 记忆，而是在 `${AKASHIC_WORKSPACE}/memory/agent-memory.db` 启用显式 Memory API 和语义检索；它只允许 Loopback 监听，并会在写入或当前 Scope 非空检索时调用与 `OPENAI_*` 相同 Provider 配置下的 Embedding 模型。未获得网络、费用和部署授权时必须保持 `DISABLED`。

三个外层 `AGENT_MEMORY_MAX_*` 值分别限制单文件 UTF-8 字节、全部 Context 字符和单检索块字符。Embedding 配置限制逻辑模型、维度和单条输入 Code Point；Retrieval 配置限制 Top-K、cosine 阈值、Hotness、候选总数和注入字符。所有范围在启动时校验，`MAX_INJECTED_CHARACTERS` 不得超过外层 `MAX_RETRIEVED_CHARACTERS`。`AGENT_MEMORY_RECALL_MODE` 仍默认 `DISABLED`；只有 Java Native Memory、全局只读 Tool 和 `CURRENT_SCOPE_READ_ONLY` 三者同时显式开启时，才注册当前 Turn `tool_search` 后可见的 `recall_memory`。它没有 DML、跨 Scope、Optimizer、Scheduler 或自动摄入权限。

`AGENT_TOOL_MAX_ITERATIONS` 表示一次聊天最多允许多少次模型调用，必须大于零，默认 `6`。其余 `AGENT_TOOL_*` 配置分别限制运行模式、单响应/单轮调用数、等待加执行的共享超时、JVM 并发许可、参数 UTF-8 字节数、结果 Unicode 字符数和审批请求有效期。整数必须大于零，单轮上限不得小于单响应上限，工具超时必须小于模型超时；审批有效期默认 `5m`，必须大于零且不超过 `15m`。非法值会使应用启动失败。

未显式指定配置文件，且启动目录不存在 `config.toml` 时使用此模式。

### 1.2 MCP 静态只读模式（默认关闭）

`AGENT_MCP_MODE` 默认且模板固定为 `DISABLED`。此时 Bootstrap 不读取 `AGENT_MCP_CONFIG_FILE`，不创建 SDK Client，不启动子进程、不访问 MCP 网络，也不发布 MCP Tool。仅设置配置文件路径不会改变这一行为。

R5.1 只支持 `STATIC_READ_ONLY` 和本地 stdio Server。只有在 Executable、版本、Tool Allowlist、风险、Secret 来源、数据范围和部署均单独获批后，才可以同时设置：

```dotenv
AGENT_TOOL_MODE=READ_ONLY
AGENT_MCP_MODE=STATIC_READ_ONLY
AGENT_MCP_CONFIG_FILE=/absolute/path/to/mcp-read-only.json
```

静态文件必须是无符号链接的有界普通 JSON 文件。以下结构仅说明 Schema，不代表其中 Server 已获准运行：

```json
{
  "schemaVersion": 1,
  "servers": [
    {
      "id": "docs",
      "transport": "STDIO",
      "executable": "/absolute/path/to/java",
      "arguments": ["-jar", "/absolute/path/to/docs-mcp.jar"],
      "workingDirectory": "/absolute/path/to/runtime",
      "environmentVariables": ["DOCS_MCP_TOKEN"],
      "tools": {
        "search": {"enabled": true, "risk": "READ_ONLY"}
      }
    }
  ]
}
```

配置只能保存环境变量名，Secret 值必须由进程环境提供。子进程环境会先清空，再复制显式 Allowlist；不会继承 `PATH`、`HOME`、Provider Key 或云凭证。Executable 与 Working Directory 必须是绝对路径；禁止 Shell、`sh -c`、`npx` 动态下载、相对命令、单字符串命令行、HTTP Transport 和非 `READ_ONLY` 风险。

`AGENT_MCP_REQUEST_TIMEOUT` 必须小于 `AGENT_TOOL_TIMEOUT`，`AGENT_MCP_CONNECT_TIMEOUT` 必须小于 `AGENT_MODEL_TIMEOUT`。其余 MCP 配置分别限制 Server/Tool/分页数量、关闭时间、Schema/Wire 字节和单 Server 并发数；范围以 [MCP 只读客户端契约](../contracts/mcp-client-tool-runtime.md)为准，非法值会在读取 Server 配置或启动子进程前失败。

Runtime 在应用启动时发现工具并形成不可变快照。`tools/list_changed` 会使对应 Wrapper 进入 `STALE` 并拒绝后续调用；断线调用不重放，下一次新调用最多尝试一次有界重连，且 Catalog 指纹必须不变。R5.1 没有动态增删 API、后台无限重试或 MCP 状态 HTTP 端点。SDK 包日志在生产配置中关闭，Server 原始 stdout、stderr、命令、路径和错误正文不得用于排障输出。

### 1.3 Telegram 渠道（默认关闭）

`AGENT_TELEGRAM_ENABLED` 的模板值固定为 `false`。Disabled 时 Servlet 应用只启动空的
`ChannelHost`，不会读取 `AGENT_TELEGRAM_BOT_TOKEN`、创建 `HttpClient`、访问 Telegram 或
启动 Telegram Worker；CLI Non-Web 模式即使误设 Enabled 也完全不装配 Telegram。只读配置
检查在 Spring Context 之前退出，同样不会读取 Token 或启动渠道。

R6.3 离线测试只使用固定假 Token、注入的 Fake API 和 Loopback `HttpServer`。可以运行：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw -pl agent-bootstrap -am \
  -Dtest=TelegramBootstrapTest,TelegramChannelAdapterTest,TelegramChannelFailureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl agent-bootstrap -am -Pcompat \
  -Dtest=TelegramGoldenFixtureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl agent-bootstrap -am \
  -Dit.test=TelegramChannelIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

2026-07-16 的本地离线验收结果为：Golden 聚焦 25 个测试、Loopback Channel 纵向集成 4 个；
最终默认 455 个（413 单元、42 集成）、`failure` 119 个（113 单元、6 集成）、`compat` 519 个
（476 单元、43 集成）全部通过。运行这些命令不需要真实 Token，也不得临时设置真实 Token。

启用配置要求 `AGENT_TELEGRAM_ALLOW_FROM` 是逗号分隔的正十进制 User ID，Token 只能由
`AGENT_TELEGRAM_BOT_TOKEN` 提供；两个值都不得写入 Git、日志、异常或工单。并发、Buffer、
连接、长轮询、发送、关闭和重试预算均有启动时硬上限，且 Poll Request Timeout 必须大于
Long Poll Timeout。

当前任务没有授权真实 Token、`api.telegram.org`、真实 Chat/User/Message、真实正文、费用或
部署启用。因此不要把模板改为 Enabled，也不要执行真实渠道 Smoke。真实验证必须在独立任务中
先批准专用 Bot、测试用户/会话、网络、数据内容和撤销清理方案；离线门禁通过不表示可部署。

### 1.4 TOML 兼容模式（DeepSeek 示例）

从仓库内的安全模板创建本地配置：

```bash
cd "$(git rev-parse --show-toplevel)"
cp config.example.toml config.toml
```

根目录 `config.toml` 已被 Git 忽略。Java 只读该文件，不会格式化、迁移或写回。模板通过 `${DEEPSEEK_API_KEY}` 引用进程环境，因此 `.env` 应改为：

```dotenv
AKASHIC_WORKSPACE=./workspace-java
DEEPSEEK_API_KEY=replace-me
AGENT_TOOL_MODE=DISABLED
```

2026-07-14 修复 Spring AI Provider Options 后，DeepSeek `deepseek-v4-flash` 已通过真实 Tool Call—Java 执行—Tool Result 回送—最终文本—SQLite 提交的完整 Smoke。该结果只覆盖这一 Provider/模型组合，不自动授权部署切换；模板和现有部署继续保留 `AGENT_TOOL_MODE=DISABLED`。只有同一组合取得明确部署批准后，才能改为 `READ_ONLY`，其他模型仍须先分别完成 Smoke。

删除或注释 `.env` 中的 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL`；它们是 TOML 活动字段的最高优先级覆盖值，保留模板值会覆盖 DeepSeek 配置。

配置文件定位优先级固定为：

1. 命令行 `--agent.config-file=/absolute/or/relative/path.toml`。
2. 环境变量 `NAMEI_CONFIG_FILE`。
3. 启动目录的 `./config.toml`。
4. 都不存在时回退到环境变量模式。

相对路径以 Java 进程启动目录为基准。本手册的命令会先进入项目根目录，避免从 `docs/runbooks` 等子目录启动时找错配置。

## 2. 工作区安全

Python 和 Java 禁止同时写同一个 Workspace。R4.1/R4.2 都不授权 Java 读取或写入真实 Python Workspace；测试只使用临时目录或 Java 专用目录。旧 Python `memory2.db` 可以被 Java 忽略，但不得由 Java 读取、迁移或自动删除。

注意：`AGENT_MEMORY_MODE=READ_ONLY` 只表示 Markdown Profile Adapter 不写文件，不表示整个应用只读。应用仍会在 `${AKASHIC_WORKSPACE}` 创建或更新 `sessions.db`。不得通过切换该模式规避工作区备份和隔离要求。

第一次写入既有工作区前：

1. 停止所有 Python 和 Java Agent 进程。
2. 确认没有进程持有 SQLite 文件。
3. 同时备份 `sessions.db`、`sessions.db-wal` 和 `sessions.db-shm`；若已存在 Java `memory/agent-memory.db`，也一起备份其数据库、`-wal` 和 `-shm`。不存在的伴随文件需记录这一事实。
4. 在备份副本或全新的 Java 专用目录中完成兼容性验证。
5. 未经单独批准，不得把 Java 指向真实 Python Workspace。

推荐为 Java 创建独立目录：

```bash
cd "$(git rev-parse --show-toplevel)"
mkdir -p ./workspace-java
```

并设置：

```dotenv
AKASHIC_WORKSPACE=./workspace-java
```

## 3. 构建与启动

构建项目：

```bash
cd "$(git rev-parse --show-toplevel)"
set -a && source .env && set +a
./mvnw clean verify
```

启动前先执行只读配置检查：

```bash
(
  cd "$(git rev-parse --show-toplevel)" || exit 1
  [[ -f .env ]] || { echo "未找到项目根目录下的 .env，请先从 .env.example 创建"; exit 1; }
  [[ -f agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar ]] || \
    { echo "未找到可执行 JAR，请先运行 ./mvnw clean verify"; exit 1; }
  set -a
  source .env || exit 1
  set +a
  java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar \
    --agent.config-check
)
```

显式检查其他 TOML 文件时，在同一条 `java -jar` 命令末尾追加 `--agent.config-file=/path/to/config.toml`。检查成功返回退出码 `0`，配置无效返回 `2`。输出只包含配置模式、规范化路径、字段来源、Secret 状态、Deferred/未知路径和诊断码；不会创建 Spring Application Context、Workspace、SQLite、HTTP Server 或模型客户端。

启动应用：

```bash
(
  cd "$(git rev-parse --show-toplevel)" || exit 1
  [[ -f .env ]] || { echo "未找到项目根目录下的 .env，请先从 .env.example 创建"; exit 1; }
  [[ -f agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar ]] || \
    { echo "未找到可执行 JAR，请先运行 ./mvnw clean verify"; exit 1; }
  set -a
  source .env || exit 1
  set +a
  java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar
)
```

圆括号会在独立的子 Shell 中加载 `.env`，不会改变当前终端的目录、`set -a` 状态或已导出的环境变量。停止应用时按 `Ctrl+C`。

应用默认监听 `127.0.0.1:8080`。不要通过配置把它改为 `0.0.0.0`；MVP 没有远程认证和 TLS。

### 3.1 启动本地 CLI

使用同一套 Provider、Java 专用 Workspace、Memory、Tool 和 MCP 配置，以显式 `--cli` 启动：

```bash
(
  cd "$(git rev-parse --show-toplevel)" || exit 1
  [[ -f .env ]] || { echo "未找到项目根目录下的 .env，请先从 .env.example 创建"; exit 1; }
  [[ -f agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar ]] || \
    { echo "未找到可执行 JAR，请先运行 ./mvnw clean verify"; exit 1; }
  set -a
  source .env || exit 1
  set +a
  java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar --cli
)
```

CLI 模式使用 `WebApplicationType.NONE`，不会监听 HTTP 端口；默认 Web 启动命令保持不变。stdin 按严格、有界 UTF-8 行读取，空白行忽略，一次只执行一个 Turn。Delta 直接预览；若最终权威快照与预览不同，会另起一行输出完整结果。失败和取消只输出稳定码，不输出 Provider 原始正文。

输入 EOF 会在当前 Turn 完成后正常退出。按 `Ctrl+C` 时 Spring/JVM Shutdown 会以 `SHUTDOWN` 取消活动 Turn；stdout 关闭会以 `CHANNEL_DISCONNECTED` 取消，并且不会提交半截回答。CLI 仍会调用真实 Provider 并写入 Java `sessions.db`，所以必须继续遵守网络/费用授权和 Workspace 隔离要求；`--cli` 不是只读配置检查模式。

若同时传入 `--agent.config-check --cli`，配置检查优先，进程不会创建 Spring Context、CLI Runner、Workspace、SQLite 或 Provider Client。

## 4. 本地检查

健康检查只验证应用和 SQLite，不调用模型：

```bash
curl --fail-with-body http://127.0.0.1:8080/actuator/health
```

聊天请求：

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: local-demo-1' \
  -d '{"sessionId":"demo","message":"你好"}' \
  http://127.0.0.1:8080/api/v1/chat
```

R6.5 控制面后端默认保持 `AGENT_CONTROL_PLANE_MODE=DISABLED`。此时不存在
`/api/v1/control/**` Mapping、Operator Session、活动 Turn Registry、SSE Subscriber 或控制面后台
线程。经独立部署批准改为 `LOOPBACK` 时，`server.address` 必须显式为 `127.0.0.1` 或 `::1`；服务
仍会拒绝远端地址、非 Loopback Host、跨 Origin、CORS Preflight 和代理 Header 授权。

控制面配置只接受 `.env.example` 中的有界值：Session 默认 `15m`/最多 `4` 个，活动 Turn 最多
`128`，Terminal Tombstone 保留 `5m`/最多 `1024`，全局 SSE Subscriber 最多 `8`、每订阅队列
`64`，Heartbeat `15s`、流生命期 `15m`、共享关闭时间 `2s`。启用后先通过
`POST /api/v1/control/session` 在 Loopback 创建一次性返回的 256-bit Bearer；除创建 Session 外，
`status`、`turns`、目标 `cancel`、future-only `events` 和 Session 注销均要求
`Authorization: Bearer ...`。Token 只保留摘要，不得写入命令历史、日志、URL、配置或文档。

该后端只观察当前 JVM 中存活的 Telegram 普通/可靠 Turn，不提供历史重放，也不能控制 CLI、同步
`/api/v1/chat` 或 `EXECUTION_UNKNOWN`。真实 Telegram、远程代理、CLI+Web 和前端在后端合入前
继续冻结；不要把本节当作生产启用授权。

当 `AGENT_TOOL_MODE=READ_ONLY` 且 MCP 保持 `DISABLED` 时，生产注册表只有只读 `current_time`。经单独批准启用 `STATIC_READ_ONLY` 后，Bootstrap 会把通过本地 Allowlist、风险与 Schema 门禁的 MCP Tool 加入同一个不可变快照；MCP Server 不能改变风险、审批、预算、循环或提交语义。工具中间消息不会写入 SQLite，最终仍只保存一组 `user/assistant`。全局 `AGENT_TOOL_MODE=DISABLED` 时不注册任何工具，也不向模型发送 Tool Definition。

`APPROVAL_REQUIRED` 是已经实现的安全框架模式，不是可用的人类审批功能。当前生产装配只有 `DenyAllApprovalPort`，没有 Approval API/UI/Channel、生产 Durable Ledger 或任何 `WRITE`/`EXTERNAL_SIDE_EFFECT` Tool；因此切换该值不会出现审批入口，也不会获得真实副作用能力。模板与现有部署继续保持 `DISABLED`。未来只有在 Approval Channel、Durable Ledger、具体 Tool Capability/Sandbox Contract 和部署批准全部具备后，才会编写相应启用手册。

Memory 默认同样保持 `DISABLED`。`READ_ONLY` 会把 `SELF.md` 和 `MEMORY.md` 加入 System Prompt，把去除 `Recent Turns` 的 `RECENT_CONTEXT.md` 放入历史之后、当前用户消息之前的临时 Context Frame。`JAVA_NATIVE` 改用独立 `agent-memory.db`：显式 API 写入后，聊天只对当前真实 User 消息做 Scope/cosine/Hotness 检索，并把有界结果放在历史之后、当前消息之前；同一 Tool Loop 复用该 Frame。两种 Frame 都不会提交到会话 SQLite，普通聊天也不会自动写 Memory。

`JAVA_NATIVE` 要求 `server.address` 显式配置为 Loopback；为空、`0.0.0.0`、局域网或公网地址都会在创建 Java Memory DB 前启动失败。当前无认证，因此不得通过代理或端口转发暴露 Memory API。旧 `HISTORY.md`、`PENDING.md`、Journal 和 `memory2.db` 始终不读取。

以下 API 仅用于已经获得 Provider/费用、Java 专用 Workspace 和部署启用授权的 Loopback 实例；默认 `DISABLED` 下会返回 HTTP 503：

```bash
curl --fail-with-body \
  -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"memory-write-001","type":"PREFERENCE","content":"回答时先给结论","emotionalWeight":2}' \
  http://127.0.0.1:8080/api/v1/sessions/demo/memories

curl --fail-with-body \
  http://127.0.0.1:8080/api/v1/sessions/demo/memories

curl --fail-with-body \
  -X DELETE \
  -H 'Idempotency-Key: memory-delete-001' \
  http://127.0.0.1:8080/api/v1/sessions/demo/memories/memory-id-from-list
```

PUT 的重试必须复用相同 `requestId` 和完全相同的业务参数；DELETE 的重试必须复用相同 `Idempotency-Key` 和 Item ID。相同幂等键绑定不同参数会返回 HTTP 409，不能通过生成新键盲目绕过未知结果。

除 `/actuator/health` 外的 Actuator 端点应返回 `404`。

## 5. 测试 Profile

从 2026-07-17 起，测试 Profile 使用互斥集合，避免同一用例在默认、`failure` 和 `compat` 中重复执行：

| 命令 | 实际选择 | 是否访问真实外部资源 |
| --- | --- | --- |
| `./mvnw clean verify` | 未标记的常规单元/集成测试 | 否 |
| `./mvnw -Pfailure verify` | 仅 `@Tag("failure")` | 否，只允许 Fake/Stub/临时数据库 |
| `./mvnw -Pcompat verify` | 仅 `@Tag("compat")` | 否，只读取批准的 Fixture/临时副本 |
| `./mvnw -Preal-model-smoke verify` | 仅 `@Tag("real-model")` | 是，必须另行授权 |

单独运行 `failure` 或 `compat` 不再隐式运行默认回归。阶段或合并门禁需要完整离线证据时，依次运行前三条命令。
开发中的聚焦测试应使用一次 Maven 调用选择本 Task 的全部目标类；同一选择器同时包含常规和
`failure` 方法时，可附加 `-Dexcluded.test.groups=compat,real-model`。不要通过连续运行单个测试类增加 Maven 启动开销。

默认离线验证：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw clean verify
```

故障语义：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw -Pfailure verify
```

`failure` 独立覆盖审批错配与过期、批准后取消、Ledger 持久化故障、并发一次性消费、`UNKNOWN` 停机，Memory Embedding 零写入、幂等冲突、数据库不可用、Profile/检索/预算和安全 HTTP 映射，MCP 损坏 JSON、stdout 噪声、错误 Response ID、突然退出、Stale、Catalog 变化和关闭/重连竞态，Provider 流空闲超时/损坏、目标连接取消、背压/断开唤醒、CLI 输出/启动/关闭故障和 SQLite 半轮次隔离，以及 Telegram Poll 重试/永久失败、断开、取消与关闭竞态。MCP 故障测试只启动仓库编译的 Java Reference Server；Telegram 故障测试只使用内存 Fake API，二者都不执行真实外部变更。

Python/Java Golden 与 Schema 固定样本兼容性：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw -Pcompat verify
```

`compat` 直接读取仓库内的 `testdata/golden/`，包括只读 Context/Memory、Approval/Side Effect Golden，以及 Java-owned Memory、MCP、版本化 Message、Provider Streaming/CLI、Telegram、可靠投递和 Loopback 控制面 Fixture。生产 Java 实现会消费这些 Fixture 的 Schema、Codec、Hash、HTTP、排序、Injection、配置、命名、Schema 投影、结果、消息顺序、流预算、渠道生命周期和安全控制语义；测试不会启动 Python、访问真实模型/MCP Server/Telegram、执行真实副作用、读取真实 Workspace 或读取 `.env`。

以下 R5.1–R6.2 数字产生于互斥 Profile 生效前，`compat` 当时包含常规和故障回归；它们只作为历史验收证据保留，不能与 2026-07-17 之后的互斥测试数直接比较。

R5.1 于 2026-07-15 完成离线基线：默认 Profile 共 284 个测试（270 个单元、14 个集成），`failure` 共 63 个（62 个单元、1 个集成），`compat` 共 323 个（308 个单元、15 个集成），均为 0 Failure、0 Error、0 Skipped。MCP Integration 使用受控 Java stdio 子进程并验证结束后零孤儿进程；该结果不包含真实 Provider、真实 MCP Server、真实 Secret、真实 Workspace 或部署启用验证。

R6.1 于 2026-07-15 完成最新离线基线：默认 Profile 共 321 个测试（307 个单元、14 个集成），`failure` 共 63 个（62 个单元、1 个集成），`compat` 共 360 个（345 个单元、15 个集成），均为 0 Failure、0 Error、0 Skipped。该阶段只新增库内 Message Contract Runtime 和确定性测试，没有 CLI 启动命令、真实 Provider Delta、真实渠道、网络、消息中间件或新数据库；不能把通过这组门禁解释为流式渠道已经可部署。

R6.2 于 2026-07-15 完成最新离线基线：默认 Profile 共 363 个测试（345 个单元、18 个集成），`failure` 共 99 个（96 个单元、3 个集成），`compat` 共 402 个（383 个单元、19 个集成），均为 0 Failure、0 Error、0 Skipped。该阶段只使用本地 OpenAI-compatible HTTP Stub、Java Reference MCP Server 和临时 SQLite，验证了真实 SSE 传输形态和目标连接取消，但没有访问真实外部 Provider/渠道、Secret、付费服务或用户工作区；通过门禁不代表真实渠道可部署。

Java-owned Prompt 编排、Memory、MCP、Plugin、Proactive、Cutover、两个 Message Bus、Telegram、可靠投递、Loopback 控制面、Tool Catalog、Approval Inbox 与 Pending Operation 共 14 个 Fixture 不由 Python 生成器维护，只能在对应 Java Contract 获得新批准后人工更新并同步 Manifest。其他 Python 基准夹具只有在对应 Python 行为或已批准 Contract 变化时才重新生成：

```bash
cd "$(git rev-parse --show-toplevel)"
[[ -x ../akashic-agent/.venv/bin/python ]] || \
  { echo "未找到相邻 Python 仓库的 .venv/bin/python"; exit 1; }
../akashic-agent/.venv/bin/python tools/golden/generate.py \
  --python-repo ../akashic-agent \
  --output testdata/golden
./mvnw -Pcompat verify
```

当前 Python 生成器只重建其管理的 10 个 Python/迁移夹具，不拥有 14 个 `java-contract` 夹具，也不拥有人工维护的错误夹具。运行后必须审查 `manifest.json`，保留全部 Java-owned 与错误夹具条目并按实际文件重算 SHA-256；当前 Manifest 应有 25 个条目。不得仅因生成器重写 Manifest 而删除 Java-owned Contract。

不要把测试失败当成重新录制 Golden 的理由。提交夹具变化前，必须按 [Golden Test 夹具规范](../contracts/golden-test-fixtures.md)记录语义差异和审批证据。

真实模型 Smoke Test 不属于常规验证。只有获得真实网络调用授权并确认可能产生费用后，才运行：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw -Preal-model-smoke verify
```

测试使用临时 Workspace，并断言普通回答、`current_time` Tool Call、Java 执行、两次模型请求、最终文本以及 SQLite 仅提交最终 `user/assistant`。测试通过只形成 Provider/模型能力证据，不会修改部署的 `AGENT_TOOL_MODE`。

该 Profile 不执行真实 MCP Smoke。真实 MCP Server 验证必须另行批准 Server、Executable、版本、Allowlist、Secret、网络/费用、沙箱和数据范围；R5.1 没有可直接复制执行的真实 Smoke 命令。

## 6. 故障排查

### 启动提示缺少模型配置

环境变量模式先确认已执行：

```bash
cd "$(git rev-parse --show-toplevel)"
set -a && source .env && set +a
```

检查 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL` 是否为非空值；不要在终端输出或聊天中粘贴 `OPENAI_API_KEY`。

TOML 模式先运行 `--agent.config-check`。如果诊断为 `CONFIG_ENV_UNRESOLVED`，确认 `api_key` 使用的完整占位符（例如 `${DEEPSEEK_API_KEY}`）已经导出且非空；不要把密钥直接粘贴到排障记录。

### TOML 文件未生效

确认命令从项目根目录启动，或显式使用 `--agent.config-file`/`NAMEI_CONFIG_FILE`。命令行优先于 `NAMEI_CONFIG_FILE`，后者优先于默认 `./config.toml`。修改配置后必须重启，当前不支持热更新。

如果要回退到环境变量模式，先取消 `NAMEI_CONFIG_FILE`，并让启动目录不再存在 `config.toml`，然后提供三个 `OPENAI_*` 变量。Java 不会自动删除或改写 TOML。

### 配置检查返回退出码 2

根据 JSON 中的稳定诊断码和字段路径修复问题。报告不会返回错误原值：

- `CONFIG_FILE_NOT_FOUND`/`CONFIG_FILE_INVALID`：检查文件路径、`.toml` 扩展名和普通文件类型。
- `CONFIG_TOML_INVALID`/`CONFIG_TYPE_INVALID`：检查 UTF-8、TOML 语法和原生类型。
- `CONFIG_REQUIRED_MISSING`/`CONFIG_ENV_UNRESOLVED`：补齐必填字段或占位符环境变量。
- `CONFIG_URL_INVALID`：Base URL 必须是带 Host 的 `http` 或 `https` 绝对 URI。

### MCP 启动失败或工具不可用

先把 `AGENT_MCP_MODE` 恢复为 `DISABLED` 并重启，确认普通聊天不依赖 MCP。静态启用失败时核对全局 Tool Mode、两个超时关系、配置文件是否为绝对路径的非符号链接普通文件，以及 Executable/Working Directory 的 Real Path、权限、Server/Tool 上限和 `READ_ONLY` Allowlist。不要改用 Shell、相对命令、`npx`、全环境继承或放宽 Schema 来绕过门禁。

已发布工具在 `tools/list_changed` 后会安全变为不可用，Catalog 改变的重连也不会热替换；恢复方式是审查新 Catalog、更新 Contract/Allowlist 后重启。公开错误只会说明 MCP 工具不可用、执行失败、类型不支持或通信超限，不会显示 Server 正文。不得为了排障打开 SDK/Server 原始内容日志或把 Secret、命令、路径、stdout/stderr 粘贴到工单。

### Telegram 启用失败

先恢复 `AGENT_TELEGRAM_ENABLED=false` 并重启，确认普通 HTTP/CLI 能力不依赖 Telegram。
只根据稳定配置字段检查 Allowlist、Token 是否存在、预算范围和两个 Poll Timeout 的大小关系；
不要输出 Token、Bot API URI、Chat/User/Message ID、Update ID、消息正文或 Telegram 原始响应。
`POLL_UNAUTHORIZED` 需要撤销并重新配置专用测试 Token，`POLL_INVALID_RESPONSE` 和
`POLL_RETRY_EXHAUSTED` 不能通过无限重试或放宽正文/Deadline 上限规避。真实网络仍须重新授权，
默认本地排障只运行 Fake/Loopback 测试。

### 模型返回 404

首先检查活动 Base URL 是否包含服务要求的 API 根路径。OpenAI 官方地址应为 `https://api.openai.com/v1`；DeepSeek 预设为 `https://api.deepseek.com/v1`。在 TOML 模式中还要确认没有遗留的 `OPENAI_BASE_URL` 覆盖配置。

### 返回 502

表示 Chat Provider 拒绝/响应无效、Tool/Approval/Ledger/检索上下文失败，或显式 Memory 写入的 Embedding 调用/响应无效。聊天 Query Embedding 失败会降级为空检索并继续，不单独返回 502；显式 Memory 写入则保证零 Item/零 Mutation 后失败。使用 `X-Request-Id` 关联安全日志；响应不会返回上游正文、Memory 正文或路径、检索查询/结果、Tool Arguments/Result、审批/幂等内部状态或 Provider Message。当前生产没有真实审批入口；若在 `APPROVAL_REQUIRED` 下遇到该错误，应保持 `DISABLED` 并检查装配，不能通过重试绕过 Fail Closed。

### 返回 504

表示模型 HTTP 调用超时，或同一 `sessionId` 的前一个请求占用会话锁过久。不要通过并发重试同一会话放大拥塞。

单个只读工具超时不会直接产生 HTTP `504`：Runtime 会向模型回送固定 `TIMEOUT` 结果并允许其生成替代回答；如果模型随后仍失败，则按对应模型错误处理。

### 返回 500

检查 Java 专用 Workspace 的目录权限、磁盘空间、`sessions.db` 和 `memory/agent-memory.db` 状态。不要手工删除 `-wal` 或 `-shm` 文件来“修复”数据库，也不要把 `memory2.db` 重命名为 `agent-memory.db`。

### Memory API 返回 503/409/404

- `503 记忆功能不可用`：当前模式是 `DISABLED`/`READ_ONLY`，或 Java Memory 未完整装配；不要用重试绕过，先恢复 `DISABLED` 并检查配置。
- `409 记忆请求幂等冲突`：同一 Session 下的 Request ID/Idempotency Key 已绑定其他参数；核对原请求，不要自动生成新键重复副作用。
- `404 记忆不存在`：Item 不存在与属于其他 Scope 使用相同安全结果；先按相同 Session 列表确认，不会暴露跨 Scope 信息。

### SQLite busy 或锁等待

确认没有 Python、第二个 Java 进程或外部 SQLite 工具写同一数据库。本项目只保证单个 Java 进程内同会话串行，不提供跨进程协调。

### 请求返回 413

HTTP 请求体不得超过 65,536 字节。消息本身去除首尾空白后最多为 32,000 个字符；不要依赖静默截断。

## 7. 日志边界

日志只允许安全的请求 ID、会话哈希、消息数量、模型名、耗时、结果和错误码。禁止记录 API Key、Authorization Header、完整 Prompt、消息正文、Session 原文或提供方错误载荷。
