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

创建本地配置：

```bash
cd "$(git rev-parse --show-toplevel)"
cp .env.example .env
```

编辑 `.env`：

```dotenv
AKASHIC_WORKSPACE=./workspace
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_KEY=replace-me
OPENAI_MODEL=gpt-4o-mini
```

`OPENAI_BASE_URL` 是 OpenAI-compatible API 根路径。OpenAI 官方地址需要包含 `/v1`。`.env` 已被 Git 忽略，禁止提交真实密钥。

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

加载环境变量并构建：

```bash
cd "$(git rev-parse --show-toplevel)"
set -a && source .env && set +a
./mvnw clean verify
```

启动应用：

```bash
cd "$(git rev-parse --show-toplevel)"
java -jar agent-bootstrap/target/agent-bootstrap-0.1.0-SNAPSHOT.jar
```

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

Python Schema 固定样本兼容性：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw -Pcompat verify
```

真实模型 Smoke Test 不属于常规验证。只有获得真实网络调用授权并确认可能产生费用后，才运行：

```bash
cd "$(git rev-parse --show-toplevel)"
./mvnw -Preal-model-smoke verify
```

## 6. 故障排查

### 启动提示缺少模型配置

确认已执行：

```bash
cd "$(git rev-parse --show-toplevel)"
set -a && source .env && set +a
```

检查三个变量是否为非空值；不要在终端输出或聊天中粘贴 `OPENAI_API_KEY`。

### 模型返回 404

首先检查 `OPENAI_BASE_URL` 是否包含服务要求的 API 根路径。OpenAI 官方地址应为 `https://api.openai.com/v1`，而不是只到域名。

### 返回 502

表示提供方拒绝请求、返回服务错误、非法 JSON、缺失响应项或空回答。使用 `X-Request-Id` 关联安全日志；响应不会返回上游正文。

### 返回 504

表示模型 HTTP 调用超时，或同一 `sessionId` 的前一个请求占用会话锁过久。不要通过并发重试同一会话放大拥塞。

### 返回 500

检查 Java 专用 Workspace 的目录权限、磁盘空间和 SQLite 文件状态。不要手工删除 `-wal` 或 `-shm` 文件来“修复”数据库。

### SQLite busy 或锁等待

确认没有 Python、第二个 Java 进程或外部 SQLite 工具写同一数据库。本项目只保证单个 Java 进程内同会话串行，不提供跨进程协调。

### 请求返回 413

HTTP 请求体不得超过 65,536 字节。消息本身去除首尾空白后最多为 32,000 个字符；不要依赖静默截断。

## 7. 日志边界

日志只允许安全的请求 ID、会话哈希、消息数量、模型名、耗时、结果和错误码。禁止记录 API Key、Authorization Header、完整 Prompt、消息正文、Session 原文或提供方错误载荷。
