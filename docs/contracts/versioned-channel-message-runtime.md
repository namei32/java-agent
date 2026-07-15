# 版本化渠道消息与流式运行时契约

- 状态：已批准
- 契约版本：1
- 批准日期：2026-07-15
- 批准记录：用户要求完成 MCP PR 与远程 CI 后，从 R6 的版本化 Message Contract Fixture 开始连续 TDD 实现
- 阶段：R6.1
- 前置契约：[核心消息、生命周期与 Tool 契约](core-message-lifecycle-tool.md)
- 关联 ADR：[ADR-0007：采用项目自有的有界渠道消息协议](../adr/0007-use-project-owned-bounded-channel-message-protocol.md)
- 关联设计：[版本化渠道消息 Contract Runtime 设计](../specs/2026-07-15-versioned-channel-message-runtime-design.md)

## 1. 目的

本契约固定 Channel Adapter 与 Java Agent Core 之间的最小消息边界，使 CLI、HTTP 和未来真实渠道只负责输入输出转换，不取得 Chat、Tool、Memory、MCP、取消或会话提交的编排权。

版本 1 先固定文本被动 Turn：可信 Channel Adapter 把外部输入规范化为 `InboundMessage`，Application 把 Turn 过程投影为有序 `OutboundMessage`。MCP 和本地 Tool 位于 Agent 内部，不改变渠道协议。

## 2. 范围

包含：

- 版本化文本 `InboundMessage`。
- `TURN_STARTED`、`CONTENT_DELTA`、`TURN_COMPLETED`、`TURN_CANCELLED`、`TURN_FAILED` 出站消息。
- 每个 Turn 从 `0` 开始且严格递增的序号。
- 唯一终态、取消原因、安全错误码和有界背压。
- Channel 断开向活动 Turn 传播取消。
- Java-owned Contract Fixture 和纯离线测试。

不包含：

- 真实 Provider 流式 API、SSE/WebSocket、CLI 进程宿主或 Telegram。
- 附件、多模态、Thinking/Reasoning、主动消息或后台任务。
- 入站持久队列、跨进程投递、Exactly Once、自动重试或 Outbox。
- 新的 SQLite Schema、Tool Transcript 或 Lifecycle 持久化。
- Approval Channel、真实副作用工具、真实 Workspace、真实 MCP Server 或外部网络。

## 3. 入站消息

`InboundMessage` 版本 1 包含：

| 字段 | 规则 |
| --- | --- |
| `schemaVersion` | 必须精确为 `1` |
| `messageId` | Channel 内稳定的来源消息 ID，非空，最多 128 字符 |
| `turnId` | 本次投递的关联 ID，非空，最多 128 字符 |
| `sessionId` | 由可信 Adapter 解析的会话绑定，非空，最多 256 字符 |
| `route.channel` | 小写 ASCII 渠道名，匹配 `[a-z][a-z0-9_-]{0,31}` |
| `route.conversationId` | 渠道路由标识，非空，最多 256 字符 |
| `senderId` | 渠道内发送者标识，非空，最多 256 字符 |
| `content` | 去除首尾空白后非空，最多 32,000 字符；禁止静默截断 |
| `occurredAt` | 带时区语义的 UTC `Instant`，不得为空 |

所有标识禁止 CR、LF、NUL 和其他 ISO Control 字符。`sessionId`、`conversationId`、`senderId` 和正文都是敏感数据，禁止进入普通日志或生命周期事件。

版本 1 不接受任意 Metadata、Session Override、Media 或附件。未来增加时必须升级 Contract，不能通过未验证 Map 绕过边界。

## 4. 出站消息

所有 `OutboundMessage` 包含 `schemaVersion`、`turnId`、`sessionId`、`route`、`sequence`、`type`，并按类型限制 Payload：

| 类型 | Payload | 规则 |
| --- | --- | --- |
| `TURN_STARTED` | 无 | 必须是 `sequence=0` 的第一条消息 |
| `CONTENT_DELTA` | `content` | 非空文本增量，只用于预览 |
| `TURN_COMPLETED` | `content` | 非空完整最终回答，是权威快照而不是追加 Delta |
| `TURN_CANCELLED` | `code` | 稳定取消原因，无正文 |
| `TURN_FAILED` | `code`、`retryable` | 稳定安全错误，无异常正文 |

取消原因固定为：

- `REQUESTED`：用户或调用方显式取消。
- `CHANNEL_DISCONNECTED`：Channel Consumer 已断开。
- `BACKPRESSURE_EXCEEDED`：有界缓冲在 Deadline 内无法接收下一事件。
- `SHUTDOWN`：应用正在关闭。

失败码固定为：

- `INVALID_REQUEST`
- `SESSION_BUSY`
- `MODEL_TIMEOUT`
- `MODEL_UNAVAILABLE`
- `INVALID_MODEL_RESPONSE`
- `TURN_LIMIT_EXCEEDED`
- `CONTEXT_UNAVAILABLE`
- `APPROVAL_UNAVAILABLE`
- `SIDE_EFFECT_STATE_UNKNOWN`
- `PERSISTENCE_FAILED`
- `INTERNAL_ERROR`

`retryable=true` 只表示用户可以发起一个新的显式请求；它不授权 Channel、Application 或 Provider 自动重放当前 Turn。

## 5. 序号与终态

每个 `turnId` 独立满足：

1. 第一条消息只能是 `TURN_STARTED`，`sequence=0`。
2. 后续消息 `sequence` 必须恰好加一，禁止缺口、重复和乱序。
3. 零个或多个 `CONTENT_DELTA` 后只能出现一个终态。
4. 终态为 `TURN_COMPLETED`、`TURN_CANCELLED` 或 `TURN_FAILED` 之一。
5. 终态后拒绝任何消息；并发完成/取消竞争只有一个调用可以取得终态。
6. `TURN_COMPLETED.content` 是完整回答；Consumer 必须用它替换预览，不能再次追加。

内部 `TurnLifecycleEvent` 继续作为安全观察协议。它与渠道 `OutboundMessage` 是不同投影；模型、Tool 和审批内部事件不自动暴露给 Channel。

## 6. 取消、断开与提交

- Channel 显式取消、断开、背压超限和关闭都必须写入同一个 `TurnCancellation`。
- 取消在模型或 Tool 执行中出现时，沿用现有 Application 取消协议；迟到结果不得进入出站消息。
- 若取消在 Conversation Commit 前被观察到，本轮不提交。
- 若 Commit 已完成并且 Application 已取得 `ChatResult`，`TURN_COMPLETED` 胜出；之后到达的取消不能把已提交 Turn 改写为取消。
- Channel 断开后可能无法收到终态，但仍必须取消活动工作并拒绝新的出站消息。
- 版本 1 不自动重放断线 Turn，也不承诺跨进程恢复。

## 7. 背压

- 每个活动 Turn 使用有界缓冲，容量必须为正且有上限。
- Producer 发布时最多等待配置的正 Deadline；禁止无界等待和静默丢弃 Delta。
- 缓冲超限时原子记录 `BACKPRESSURE_EXCEEDED` 取消并抛出稳定的投递异常。
- Consumer 只按严格序号读取；错误 Turn、错误 Session、错误 Route、重复终态或乱序消息 Fail Closed。
- 背压异常不包含消息正文、路由原文或缓冲内容。

## 8. 错误与敏感数据

- Channel 只看到稳定 `code` 和 `retryable`，看不到异常类型、消息、堆栈、Provider Payload、Prompt、Tool Arguments/Result 或 Memory 正文。
- `Thinking` 和 Provider Reasoning 不进入版本 1 Contract。
- 日志只允许 `turnId` 的安全哈希、序号、类型、耗时和稳定错误码；禁止记录原始 `turnId`、Session、Route、Sender 或 Content。
- 未识别的 Runtime Exception 映射为 `INTERNAL_ERROR`，不能把 `getMessage()` 投影到 Channel。

## 9. Fixture 与兼容规则

Java-owned Fixture 位于 `testdata/golden/message-bus/versioned-channel-message.json`，至少覆盖：

- 最小合法入站消息和全部字段投影。
- 未知版本、空值、超限、非法 Channel 和控制字符。
- Started/Delta/Completed、无 Delta 直接完成、取消和失败。
- 序号缺口、重复、乱序、Delta/终态前缺少 Started、终态后事件和终态竞争。
- 断开、背压和安全错误映射。

任何字段、枚举、长度、顺序、终态、取消、背压或失败语义变化，都必须修改本契约、Fixture Hash 和相应测试并重新批准。增加可选字段也属于 Contract 变化；版本 1 不以“忽略未知字段”代替显式升级。

## 10. 完成边界

R6.1 完成表示消息 Contract Runtime 可供后续 Channel Adapter 使用，不表示 R6 整体完成。R6.2 才接入本地 CLI 和真实 Provider 流式 Delta；真实渠道、Dashboard 流式传输和跨进程投递继续按独立范围验收。
