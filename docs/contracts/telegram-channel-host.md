# Telegram Channel Host 契约

- 状态：已批准，实施中
- 契约版本：1
- 日期：2026-07-16
- 阶段：R6.3
- 批准记录：用户于 2026-07-16 明确批准本 Contract、Spec、ADR 和实施计划，并要求开始连续 TDD
- 批准范围：JDK HTTP 长轮询、数值身份路由、Secret 延迟读取和纯离线 Fake Server 实现
- 未授权范围：真实 Token、真实 Telegram 网络、真实用户数据和真实 Smoke
- 前置契约：[版本化渠道消息与流式运行时契约](versioned-channel-message-runtime.md)
- 关联 ADR：[ADR-0009：Telegram 首渠道使用 JDK HttpClient 与 Bot API 长轮询](../adr/0009-use-jdk-httpclient-for-telegram-long-polling.md)
- 关联设计：[Telegram Channel Host 设计](../specs/2026-07-16-telegram-channel-host-design.md)
- 实施计划：[Telegram Channel Host 工作计划](../plans/2026-07-16-telegram-channel-host-implementation.md)
- 官方协议基线：[Telegram Bot API](https://core.telegram.org/bots/api)

## 1. 目的

本契约固定 R6.3 的统一 `ChannelHost` 生命周期和第一个 Telegram 文本渠道，使 Telegram 与本地 CLI 复用同一套 `InboundMessage`、`OutboundMessage`、`MessageTurnService`、Tool/Memory/MCP、取消和会话提交语义。

Telegram Adapter 只负责可信身份映射、Bot API 输入输出和网络生命周期，不取得 Agent Loop 编排权。R6.3 优先完成可验证的主线纵向切片；富文本、附件、群聊和实时编辑等细节后置。

批准本草案只授权离线 Fake Server 实现。它不授权读取真实 Token、连接 Telegram、处理真实用户消息、产生费用或部署。

## 2. 本次需要批准的决策

1. **传输**：不引入第三方 Telegram Java SDK；使用 JDK 21 `HttpClient` 直接调用官方 HTTPS Bot API。
2. **接收模式**：版本 1 使用 `getUpdates` 长轮询，不开放 Webhook、远程监听端口或公网 TLS 入口。
3. **身份**：只接受私聊文本；启用时必须配置非空的十进制 Telegram User ID Allowlist，不接受可变 Username 作为授权身份。
4. **路由**：`conversationId=<chat_id>`、`sessionId=telegram:<chat_id>`、`senderId=<user_id>`，普通正文不能覆盖这些值。
5. **输出**：版本 1 使用无 `parse_mode` 的纯文本；消费并验证 Delta，但 Telegram 网络只发送权威终态，不进行消息编辑式实时预览。
6. **可靠性边界**：只提供进程内有界去重与并发保护；不新增 Inbox/Outbox、SQLite Schema、Exactly Once 或重启自动重放。
7. **实现位置**：`ChannelHost` 和 Telegram Adapter 放入 `agent-bootstrap`，不新增 Maven 模块、第三方依赖或部署单元。
8. **Secret**：只有 `enabled=true` 时才读取 `AGENT_TELEGRAM_BOT_TOKEN`；默认配置不解析 Token、不创建网络客户端、不启动后台线程。

## 3. 范围

R6.3 版本 1 包含：

- 通用 `ChannelAdapter`/`ChannelHost` 启停、健康、故障隔离和反向关闭协议。
- Telegram `getUpdates` 长轮询和 `sendMessage` 纯文本投递。
- 私聊、文本、数值 Allowlist、可信 Route/Session/Sender 映射。
- `/cancel` 与 `/stop` 对当前会话活动 Turn 的显式取消。
- 有界活动 Turn、Buffer、HTTP 正文、超时、重连和限流等待。
- 终态纯文本分片、安全错误码和发送失败隔离。
- Java-owned Telegram Contract Fixture、Fake Bot API Server 和离线集成测试。

R6.3 版本 1 不包含：

- 真实 Token、真实 Telegram 网络或真实用户数据 Smoke。
- 群组、频道、Topic、Business Message、Inline Query、Callback Query 或主动消息。
- 图片、语音、文件、位置、Reply 引用、Reaction、编辑消息或删除消息。
- Markdown/HTML `parse_mode`、Entity 转换、Typing、实时 Delta 消息编辑或 Tool/Thinking 展示。
- Webhook、公网监听、认证代理、本地 Bot API Server 或 MTProto。
- Username 到 Chat ID 的持久索引、跨重启去重、可靠投递、Outbox 或自动重放。
- 新数据库表、消息中间件、Scheduler、Plugin、Approval Channel 或副作用工具。

## 4. Channel Host 生命周期

每个 `ChannelAdapter` 暴露稳定名称、`start()`、`stopAccepting()`、`close()` 和安全状态快照。状态固定为：

- `NEW`
- `STARTING`
- `RUNNING`
- `DEGRADED`
- `FAILED`
- `STOPPING`
- `STOPPED`

规则：

1. `ChannelHost` 按注册顺序启动 Adapter，按反向顺序关闭。
2. 某个 Adapter 的运行时启动失败记录稳定状态，不阻止其他 Adapter 或既有 HTTP/CLI 能力启动。
3. 配置或 Secret 缺失属于启用错误，必须在创建 Adapter 时 Fail Closed，不能悄悄退化为开放渠道。
4. 关闭顺序固定为：停止新入站 -> 终止长轮询 -> 以 `SHUTDOWN` 取消活动 Turn -> 有界 Join -> 释放资源。
5. Adapter 关闭失败不得阻止其他 Adapter 继续关闭；Host 只保留稳定错误码，不保留原始异常正文。
6. 空 Host 启动后可以处于 `RUNNING`，但 Adapter 快照为空，且不创建线程、不访问网络；关闭后进入 `STOPPED`。
7. CLI Non-Web 模式不装配 Telegram Host，即使外部环境意外设置了 Telegram 启用变量。

## 5. Telegram 入站

### 5.1 Bot API 请求

`getUpdates` 固定发送：

- `allowed_updates=["message"]`，不能沿用服务端历史过滤配置。
- 正的长轮询 `timeout`。
- `limit=20`。
- 当前进程内 `offset`。

Bot API 规定以高于某个 `update_id` 的 offset 请求即确认对应 Update；因此 Adapter 必须在每次成功接收后重新计算 offset，并不得用无界本地队列缓存 Update。

### 5.2 接受条件

只有同时满足以下条件的 Update 才能创建 Agent Turn：

- 存在 `message`，不存在只可由其他 Update 类型表达的编辑/删除语义。
- `chat.type` 精确为 `private`。
- `from.is_bot=false`。
- `from.id` 和 `chat.id` 都是正的 64 位整数，且两者相等。
- `from.id` 位于非空数值 Allowlist。
- `message_id` 为正整数，`date` 可转换为 UTC `Instant`。
- `text` 去除首尾空白后非空，并满足 R6.1 32,000 Code Point 上限。

不满足条件的 Update 在创建 `InboundMessage` 前拒绝。未授权输入不回复，避免把 Bot 变成身份探测或垃圾消息 Oracle。附件、群聊、编辑和未知字段不降级为文本。

### 5.3 可信映射

合法 Telegram Message 映射为：

| 项目字段 | Telegram 来源/规则 |
| --- | --- |
| `schemaVersion` | R6.1 当前版本 `1` |
| `messageId` | `telegram:<chat_id>:<message_id>` |
| `turnId` | Adapter 安全 ID 生成器产生的新 ID |
| `sessionId` | `telegram:<chat_id>` |
| `route.channel` | 固定 `telegram` |
| `route.conversationId` | 十进制 `<chat_id>` |
| `senderId` | 十进制 `<from.id>` |
| `content` | 规范化后的 `message.text` |
| `occurredAt` | `message.date` 的 UTC `Instant` |

这些外部标识和正文均视为敏感数据，不进入普通日志、健康状态或异常文本。模型正文、Metadata 和 Tool Result 都不能修改映射。

## 6. Update、重复投递与并发边界

1. 一个 Adapter 实例维护单调的下一 `offset` 和有界进程内已接收窗口；同一或更旧 `update_id` 在同一进程生命周期内不再次启动 Turn。
2. Update 只有在被安全拒绝、控制命令已处理，或 Turn Worker 已成功取得容量并启动后，才推进内存 offset。
3. 每个 Conversation 最多一个活动 Turn；全 Adapter 活动 Turn 数有硬上限，默认 `8`，最大 `32`。
4. 同会话已有活动 Turn或全局容量耗尽时，不启动第二个 Agent Turn；只向已授权私聊发送固定 `SESSION_BUSY` 提示，且不自动重放。
5. Worker 启动失败释放会话占用和全局许可，并产生安全 Adapter 错误；不得泄漏许可或把 Update 标记为已执行。
6. R6.3 不持久化 offset、Update 占用或投递结果。进程在 Turn 执行中崩溃时可能丢失或在重启后再次收到 Update；该窗口必须在 R6.4 Inbox/Outbox Contract 中解决。
7. 不宣称 Exactly Once。R6.3 只证明单进程运行期内的 At-Most-One-Start。

## 7. 取消

- 已授权私聊中的精确 `/cancel` 或 `/stop` 是 Adapter 控制命令，不作为普通 Prompt 进入 Agent。
- 若该会话存在活动 Turn，Adapter 以 `REQUESTED` 触发同一个 `TurnCancellation`；Provider、Tool/MCP 和提交边界沿用 R6.1/R6.2 传播。
- 若没有活动 Turn，只发送固定“当前没有可取消的请求”，不创建新 Session/Turn。
- 重复取消不得覆盖第一个取消原因。
- 长轮询连续失败超过预算时，Adapter 进入 `FAILED`，停止新入站并以 `CHANNEL_DISCONNECTED` 取消全部活动 Turn。
- Host/JVM 关闭使用 `SHUTDOWN`，不得被之后的网络错误覆盖。

为支持显式渠道取消，`BoundedOutboundBuffer` 可以新增“只取消 Token、仍允许唯一取消终态入队”的受控方法；它不得关闭 Buffer 或清空已经排队的严格序列。

## 8. Telegram 出站

### 8.1 Message Contract 消费

Telegram Consumer 必须用 R6.1 `OutboundSequenceValidator` 验证完整序列：

- `TURN_STARTED`：不调用 Telegram。
- `CONTENT_DELTA`：按序消费并受现有预算约束，但版本 1 不调用 Telegram；所有预览合并为最终权威快照。
- `TURN_COMPLETED`：发送完整权威 `content`。
- `TURN_CANCELLED`：发送固定 `请求已取消（<CODE>）`。
- `TURN_FAILED`：发送固定 `请求失败（<CODE>）`；只有 Contract 的 `retryable` 可决定是否附加“请重新发送”，不能附加异常正文。

不发送 Thinking、Tool Arguments/Result、Memory、Prompt、Provider Payload 或异常 Message。

### 8.2 文本与分片

- 只用 `sendMessage` JSON 请求，不设置 `parse_mode`、Entities、Link Preview 或 Reply 参数。
- Telegram `sendMessage` 文本上限为 1–4096 个字符；项目采用保守的最多 4,000 个 UTF-16 Code Unit 分片，并禁止在 Surrogate Pair 中间切分。
- 分片保持原顺序，不增加序号、Markdown 标记或用户正文之外的前后缀。
- Cancel/Failed 固定文本也必须经过同一分片器，但正常情况下只有一片。
- 分片中途失败可能形成部分外部投递；Conversation Commit 不因此回滚，也不自动重跑 Agent Turn。R6.4 再处理可审计 Delivery 状态。

## 9. 网络、超时与重试

所有 HTTP 调用使用 UTF-8 JSON、显式连接和请求 Deadline，以及有界响应正文：

| 项目 | 默认值 | 硬上限 |
| --- | ---: | ---: |
| Connect Timeout | 5s | 10s |
| Long Poll Timeout | 20s | 25s |
| Poll Request Timeout | 25s | 30s |
| Send Request Timeout | 5s | 10s |
| Buffer Publish Timeout | 2s | 30s |
| Buffer Poll Timeout | 100ms | 30s |
| Shutdown Join | 5s | 30s |
| Poll Response | 1 MiB | 固定 |
| Send Response | 64 KiB | 固定 |
| Consecutive Poll Retry | 3 | 固定 |
| Retry Backoff | 250ms | 2s |
| `retry_after` Wait | 5s | 10s |

重试规则：

1. `getUpdates` 是只读请求；网络失败、Timeout、HTTP 5xx 或明确的 429 可以在同一 offset 上有界重试。
2. 成功 Poll 会重置连续失败计数；超过预算后 Adapter 进入 `FAILED`，不无限后台重连。
3. `sendMessage` 只在 Telegram 明确返回 HTTP 429 且 `retry_after` 在预算内时重试一次；429 明确表示当前请求未获接受。
4. `sendMessage` Timeout、连接中断、HTTP 5xx 或损坏响应的外部结果未知，不得盲目重试，以免重复消息。
5. HTTP 401/403/404、`ok=false` 的非限流响应和超限正文立即作为稳定永久失败处理。
6. Bot API `description`、请求 URI 和响应原文不得进入 Core、渠道文本或日志。

## 10. 配置与 Secret

配置前缀为 `agent.channels.telegram`：

- `enabled=false`
- `allow-from=[]`
- `max-concurrent-turns=8`
- `buffer-capacity=32`
- `publish-timeout=2s`
- `poll-timeout=100ms`
- `connect-timeout=5s`
- `long-poll-timeout=20s`
- `poll-request-timeout=25s`
- `send-request-timeout=5s`
- `shutdown-timeout=5s`
- `retry-backoff=250ms`
- `max-retry-after=5s`

Token 只从 `AGENT_TELEGRAM_BOT_TOKEN` 读取，不写入 YAML、TOML、CLI 参数、Fixture、日志或异常。Token 值对象必须限制长度和字符集并将 `toString()` 固定为脱敏文本。

`enabled=false` 时：

- 空 Allowlist 合法。
- 不读取 Token。
- 不创建 `HttpClient`、Adapter Worker 或 Channel Host 后台线程。
- Actuator/配置检查不访问 Telegram。

`enabled=true` 时：

- Allowlist 必须非空且全部是正十进制 User ID。
- Token 必须存在且通过本地语法校验。
- Long Poll Timeout 必须小于 Poll Request Timeout。
- 所有预算必须为正且不超过硬上限；非法配置使启动 Fail Closed。

## 11. 安全与日志

- 日志只允许 Adapter 名、状态、稳定错误码、计数和耗时。
- 禁止记录 Token、Bot API URI、Chat/User/Message/Update ID、Session、Route、正文、响应 `description` 或响应 Body。
- `Authorization` 不使用 Header；Bot Token 位于 Telegram 规定的 URL Path，因此任何 HTTP 异常包装都必须丢弃原始 URI 文本。
- Fake Server 使用固定假 Token 和固定脱敏 ID，不使用真实聊天数据。
- 未授权输入在 Agent、SQLite、Memory、Tool 和 MCP 之前拒绝。
- Telegram Adapter 不暴露文件系统、Shell、Web 写入或副作用工具新权限。

## 12. Fixture 与验收

Java-owned Fixture 位于 `testdata/golden/channels/telegram-channel-v1.json`，至少覆盖：

- 合法私聊文本的全部可信映射。
- 非 Allowlist、Group、Bot、附件、空文本、编辑和超限输入拒绝。
- Update 重复/过旧、Worker 启动失败、同会话忙和全局容量满。
- `/cancel`、`/stop`、无活动 Turn 和 First Writer Wins。
- Started/Delta 的终态合并、权威完成纠正、取消/失败固定文本和 4,000 单位分片。
- Poll offset、响应上限、Timeout、5xx、401、429、损坏 JSON 和关闭竞态。
- 默认禁用的零 Secret 读取、零网络、零后台线程。

默认 CI 只使用 Fake Transport、本地 `HttpServer`、Fake Chat 和临时资源。离线实现退出门禁为：

1. CLI 与 Telegram 消费同一 R6.1 Message Contract，并对相同 Golden Turn 得到一致权威终态。
2. 合法 Telegram 私聊只启动一次 Agent Turn；未授权消息在业务执行前拒绝。
3. 取消、Poll 断开、发送失败和 Shutdown 不留下活动 Turn、许可或非守护 Worker。
4. 默认配置零 Telegram 网络、零 Token 读取。
5. 默认、`failure`、`compat`、依赖、Secret 和线程/Socket 审计全部通过。

以上门禁通过后只能标记“R6.3 离线实现已验证，真实 Smoke 待授权”。R6.3 真实渠道验收仍要求在独立授权任务中使用专用 Bot 和测试会话完成一次无敏感数据 Smoke；未完成前不得标记可部署或默认启用。

## 13. 独立授权门禁

以下动作即使离线实现完成也必须再次暂停并取得明确授权：

- 注入或读取真实 Bot Token。
- 连接 `api.telegram.org` 或任何真实/自建 Bot API Server。
- 使用真实 Telegram User/Chat/Message ID 或真实消息正文。
- 扩大 Allowlist、启用群聊、附件、主动消息、Webhook 或公网端口。
- 新增持久 Inbox/Outbox、自动重放或任何 Exactly Once 声明。
