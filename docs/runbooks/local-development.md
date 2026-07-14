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

`AGENT_TOOL_MAX_ITERATIONS` 表示一次聊天最多允许多少次模型调用，必须大于零，默认 `6`。其余 `AGENT_TOOL_*` 配置分别限制运行模式、单响应/单轮调用数、等待加执行的共享超时、JVM 并发许可、参数 UTF-8 字节数、结果 Unicode 字符数和审批请求有效期。整数必须大于零，单轮上限不得小于单响应上限，工具超时必须小于模型超时；审批有效期默认 `5m`，必须大于零且不超过 `15m`。非法值会使应用启动失败。

未显式指定配置文件，且启动目录不存在 `config.toml` 时使用此模式。

### 1.2 TOML 兼容模式（DeepSeek 示例）

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

Python 和 Java 禁止同时写同一个 Workspace。真实 Python Workspace 在本里程碑中只允许读取，不允许由 Java 写入。

第一次写入既有工作区前：

1. 停止所有 Python 和 Java Agent 进程。
2. 确认没有进程持有 SQLite 文件。
3. 同时备份 `sessions.db`、`sessions.db-wal` 和 `sessions.db-shm`；后两个文件不存在时记录这一事实。
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

`READ_ONLY` 模式下生产注册表只有只读 `current_time`。模型可在需要当前 UTC 时间时调用它；工具中间消息不会写入 SQLite，最终仍只保存一组 `user/assistant`。`DISABLED` 模式不注册工具，也不向模型发送 Tool Definition。

`APPROVAL_REQUIRED` 是已经实现的安全框架模式，不是可用的人类审批功能。当前生产装配只有 `DenyAllApprovalPort`，没有 Approval API/UI/Channel、生产 Durable Ledger 或任何 `WRITE`/`EXTERNAL_SIDE_EFFECT` Tool；因此切换该值不会出现审批入口，也不会获得真实副作用能力。模板与现有部署继续保持 `DISABLED`。未来只有在 Approval Channel、Durable Ledger、具体 Tool Capability/Sandbox Contract 和部署批准全部具备后，才会编写相应启用手册。

除 `/actuator/health` 外的 Actuator 端点应返回 `404`。

## 5. 测试 Profile

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

`failure` 独立覆盖审批错配与过期、批准后取消、Ledger 持久化故障、并发一次性消费和 `UNKNOWN` 停机；这些测试使用内存 Fake，不会执行真实外部变更。

Python/Java Golden 与 Schema 固定样本兼容性：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw -Pcompat verify
```

`compat` 直接读取仓库内的 `testdata/golden/`，包括 Approval/Side Effect Golden；不会启动 Python、访问模型、执行真实副作用或读取 `.env`。只有在 Python 基准或已批准 Contract 发生变化时才重新生成夹具：

```bash
cd "$(git rev-parse --show-toplevel)"
[[ -x ../akashic-agent/.venv/bin/python ]] || \
  { echo "未找到相邻 Python 仓库的 .venv/bin/python"; exit 1; }
../akashic-agent/.venv/bin/python tools/golden/generate.py \
  --python-repo ../akashic-agent \
  --output testdata/golden
./mvnw -Pcompat verify
```

不要把测试失败当成重新录制 Golden 的理由。提交夹具变化前，必须按 [Golden Test 夹具规范](../contracts/golden-test-fixtures.md)记录语义差异和审批证据。

真实模型 Smoke Test 不属于常规验证。只有获得真实网络调用授权并确认可能产生费用后，才运行：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw -Preal-model-smoke verify
```

测试使用临时 Workspace，并断言普通回答、`current_time` Tool Call、Java 执行、两次模型请求、最终文本以及 SQLite 仅提交最终 `user/assistant`。测试通过只形成 Provider/模型能力证据，不会修改部署的 `AGENT_TOOL_MODE`。

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

### 模型返回 404

首先检查活动 Base URL 是否包含服务要求的 API 根路径。OpenAI 官方地址应为 `https://api.openai.com/v1`；DeepSeek 预设为 `https://api.deepseek.com/v1`。在 TOML 模式中还要确认没有遗留的 `OPENAI_BASE_URL` 覆盖配置。

### 返回 502

表示提供方拒绝请求、返回服务错误、非法 JSON、缺失响应项、空回答、Tool Call 超过调用预算，或 Tool Loop 在得到最终回答前耗尽迭代次数。使用 `X-Request-Id` 关联安全日志；响应不会返回上游正文、Tool Arguments、Tool Result、具体数量或 Call ID。

### 返回 504

表示模型 HTTP 调用超时，或同一 `sessionId` 的前一个请求占用会话锁过久。不要通过并发重试同一会话放大拥塞。

单个只读工具超时不会直接产生 HTTP `504`：Runtime 会向模型回送固定 `TIMEOUT` 结果并允许其生成替代回答；如果模型随后仍失败，则按对应模型错误处理。

### 返回 500

检查 Java 专用 Workspace 的目录权限、磁盘空间和 SQLite 文件状态。不要手工删除 `-wal` 或 `-shm` 文件来“修复”数据库。

### SQLite busy 或锁等待

确认没有 Python、第二个 Java 进程或外部 SQLite 工具写同一数据库。本项目只保证单个 Java 进程内同会话串行，不提供跨进程协调。

### 请求返回 413

HTTP 请求体不得超过 65,536 字节。消息本身去除首尾空白后最多为 32,000 个字符；不要依赖静默截断。

## 7. 日志边界

日志只允许安全的请求 ID、会话哈希、消息数量、模型名、耗时、结果和错误码。禁止记录 API Key、Authorization Header、完整 Prompt、消息正文、Session 原文或提供方错误载荷。
