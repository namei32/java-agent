# Telegram Channel Host 设计

- 状态：已实现并通过 PR #6 合入 `main`；真实 Smoke 待授权
- 日期：2026-07-16
- 阶段：R6.3
- 批准记录：用户于 2026-07-16 明确批准本设计并要求开始连续 TDD
- 批准范围：JDK HTTP 长轮询、数值身份、终态合并、Secret 延迟读取和纯离线实现
- 未授权范围：真实 Token、真实 Telegram 网络和真实用户数据
- Contract：[Telegram Channel Host 契约](../contracts/telegram-channel-host.md)
- ADR：[ADR-0009：Telegram 首渠道使用 JDK HttpClient 与 Bot API 长轮询](../adr/0009-use-jdk-httpclient-for-telegram-long-polling.md)

## 1. 设计目标

在不改变 R6.1 Message Contract、R6.2 Provider Streaming、现有同步 HTTP 和 SQLite 提交语义的前提下，建立一个默认关闭的通用 Channel Host，并让 Telegram 私聊文本经过同一个 `MessageTurnService` 完成端到端闭环。

本设计选择主线优先的最小切片：Telegram 消费所有严格有序的 Outbound Message，但把 Delta 合并为最终权威文本，只调用 `getUpdates` 和 `sendMessage`。实时编辑、附件、群聊、持久可靠投递和主动能力后置。

## 2. 模块与依赖

```text
agent-kernel
  InboundMessage / OutboundMessage / OutboundSequenceValidator
                       ^
                       |
agent-application      |
  MessageTurnService --+
  BoundedOutboundBuffer.requestCancellation()
                       ^
                       |
agent-bootstrap
  channel/
    ChannelAdapter
    ChannelHost
    ChannelStatusSnapshot
  telegram/
    TelegramProperties
    TelegramSecretSource
    TelegramBotToken
    TelegramBotApi
    JdkTelegramBotApi
    TelegramUpdateMapper
    TelegramTerminalRenderer
    TelegramChannelAdapter
```

约束：

- 不新增 Maven 模块或第三方生产依赖。
- `agent-kernel`/`agent-application` 不引用 Telegram、Spring、HTTP Client 或 JSON 类型。
- Telegram/Spring 类型只存在于 `agent-bootstrap`。
- `ChannelHost` 和 `TelegramChannelAdapter` 的核心生命周期类不实现 Spring 接口；Spring 只负责条件装配和 Bean 生命周期调用。
- `JdkTelegramBotApi` 使用 JDK `HttpClient` 和项目已有 Jackson，不引入 Telegram SDK。

若后续出现第二个复杂网络渠道，再依据重复代码证据决定是否抽取 `adapter-channel-*` 模块；R6.3 不预先扩张 Reactor。

## 3. 通用 Channel Host

### 3.1 `ChannelAdapter`

接口保持同步、可控：

```java
public interface ChannelAdapter {
  String name();
  void start();
  void stopAccepting();
  ChannelStatusSnapshot snapshot();
  void close();
}
```

`start()` 只启动 Adapter 自己的受控 Worker，不执行 Agent 业务；`stopAccepting()` 幂等地阻止新入站；`close()` 完成有界停止。状态快照只携带状态、稳定错误码、活动 Turn 数和连续网络失败数，不含异常、标识或正文。

### 3.2 `ChannelHost`

Host 持有不可变 Adapter 列表和自身 `NEW/RUNNING/STOPPED` 状态：

1. `start()` 只能执行一次，逐个调用 Adapter。
2. Adapter `start()` 抛出的 Runtime Exception 被映射为该 Adapter 的安全失败快照；Host 继续启动下一 Adapter。
3. `close()` 先对所有已启动 Adapter 正序调用 `stopAccepting()`，再反序 `close()`。
4. 每个关闭异常被隔离；Host 最终进入 `STOPPED`。
5. 重复关闭返回，不重新运行生命周期。

Host 不创建统一全局队列或线程。每个 Adapter 自己负责活动 Turn 上限和网络 Worker，避免单渠道积压耗尽全局资源。

## 4. Telegram 配置与装配

### 4.1 `TelegramProperties`

使用 `@ConfigurationProperties("agent.channels.telegram")`。构造阶段验证 Contract 中的所有预算和关系：

- Disabled 时不要求 Allowlist。
- Enabled 时 Allowlist 非空、仅正十进制 Long，去重后不可为空。
- `longPollTimeout < pollRequestTimeout`。
- `maxConcurrentTurns`、Buffer 和各 Duration 均满足硬上限。

Properties 不含 Token 字段，避免 Spring Binder 在 Disabled 路径解析 Secret。

### 4.2 Secret 延迟读取

`TelegramSecretSource` 是单方法接口，生产实现读取 `AGENT_TELEGRAM_BOT_TOKEN`。只有满足以下条件时才调用：

1. 当前是 Servlet Web Application。
2. `agent.channels.telegram.enabled=true`。
3. Telegram Adapter Bean 正在创建。

`TelegramBotToken` 使用普通 final class 而不是 record，避免自动生成包含值的 `toString()`。它只向 `JdkTelegramBotApi` 提供包内原始值，并在所有可观察文本中显示 `[REDACTED]`。

### 4.3 Spring 装配

新增 `TelegramChannelConfiguration`：

- `@ConditionalOnWebApplication(type = SERVLET)`。
- `@ConditionalOnProperty(... enabled=true)` 只创建 Token、API 和 Adapter。
- 通用 `ChannelHost` 可以在 Servlet 模式装配空列表；空列表启动无副作用。
- Bean 使用 `initMethod="start"`、`destroyMethod="close"` 或等价受控生命周期。
- CLI Non-Web Context 不包含 Telegram API、Adapter 或 Worker。

默认 `application.yml` 只声明安全预算和 `enabled=false`，不引用 Token 环境变量。`.env.example` 只放空的 `AGENT_TELEGRAM_BOT_TOKEN=` 示例。

## 5. Bot API 边界

### 5.1 项目值

`TelegramBotApi` 不向上暴露 Jackson Tree 或 HTTP 类型：

```java
interface TelegramBotApi {
  List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout);
  void sendMessage(long chatId, String text);
}
```

项目记录只保留映射所需字段：

- `TelegramUpdate(updateId, message)`。
- `TelegramMessage(messageId, occurredAt, chatId, chatType, senderId, senderBot, text)`。

缺失/未知 Update 类型用 `message=null` 表示并由 Mapper 忽略；损坏顶层协议、非法数值或超限响应抛稳定 `TelegramProtocolException`。

### 5.2 `JdkTelegramBotApi`

生产 Client：

- Base URI 固定 `https://api.telegram.org`；测试构造器可注入 Loopback Fake Server URI。
- 方法 URI 为 `/bot<TOKEN>/getUpdates` 和 `/bot<TOKEN>/sendMessage`。
- 使用 POST + `application/json; charset=UTF-8`。
- 请求异常不得直接包装 `HttpRequest`、URI 或 Body。
- Poll 使用 `BodyHandlers.ofInputStream()`，最多读取 1 MiB；Send 最多 64 KiB。
- JSON 顶层必须为 `ok=true`；`ok=false` 只读取 `error_code` 与 `parameters.retry_after`，忽略 `description`。
- HTTP Status、Bot API `ok` 和 JSON Shape 都被映射为项目稳定 Reason；项目异常不保留可能包含 Token URI 的原始 Cause。

Client 不自动重试。是否重试取决于调用语义，由 Telegram Adapter 的 Poll/Delivery 策略决定。

### 5.3 错误类型

`TelegramApiException` 只含枚举 Reason 和可选有界 `retryAfter`：

- `UNAUTHORIZED`
- `RATE_LIMITED`
- `TIMEOUT`
- `UNAVAILABLE`
- `INVALID_RESPONSE`
- `INTERRUPTED`

异常 Message 固定，不包含远端 `description`、Status Body、请求 URI、Token 或 Chat ID，也不保留可能含 URL 的原始 Cause。中断恢复线程中断标记。

## 6. Telegram 入站 Mapper

`TelegramUpdateMapper` 接收 Update、Allowlist、Clock/ID Generator，返回以下三态之一：

- `ACCEPTED(InboundMessage)`
- `CONTROL(CANCEL)`
- `IGNORED(reason)`

Reason 是内部测试/计数枚举，不含数据。Mapper 顺序：

1. 验证私聊、非 Bot、Sender/Chat 相等和数值 Allowlist。
2. 验证 Message ID、时间和文本。
3. 先识别精确 `/cancel`、`/stop`，不创建 Inbound。
4. 普通文本通过 R6.1 生产构造器创建 Inbound。

允许 Telegram JSON 携带未知字段，但只有上述白名单字段会投影；这与 R6.1 禁止任意 Metadata 一致。

## 7. Poll、Offset 与 Turn 调度

### 7.1 Poll Worker

每个 Telegram Adapter 只有一个 Poll Worker：

```text
while accepting:
  updates = api.getUpdates(nextOffset)
  for update in updates:
    classify and dispatch
    advance nextOffset only after safe ignore/control/worker start
```

Worker 使用可注入 `ChannelThreadStarter` 创建一个有名称的 Virtual Thread，便于确定性测试启动前取消、启动失败和 Join。`start()` 先 CAS 状态再启动；Thread Starter 失败必须恢复 `FAILED` 并释放所有资源。

### 7.2 进程内去重

- `nextOffset` 初始为 `0`，不使用负 offset 丢弃历史 Update。
- 一个响应内按到达顺序处理；小于 `nextOffset` 的 Update 忽略。
- 已安全分类后更新 `nextOffset=max(nextOffset, updateId+1)`，溢出 Fail Closed。
- 额外保留最多 1,024 个 Update ID 的内存窗口用于 Fake/异常响应重复检测；不持久化。

Telegram 官方确认语义意味着下一次携带更高 offset 的 Poll 会确认先前 Update。由于 R6.3 允许 Worker 异步运行，确认发生在 Turn 完成之前；崩溃窗口明确留给 R6.4。

### 7.3 活动 Turn

Adapter 使用：

- 一个公平 `Semaphore(maxConcurrentTurns)`。
- `ConcurrentHashMap<conversationId, ActiveTurn>`。
- 每个 `ActiveTurn` 包含 Buffer、Producer/Consumer Thread 和完成信号，不包含正文副本。

普通 Update 的启动步骤：

1. 非阻塞取得全局许可。
2. 原子占用 Conversation。
3. 创建 `BoundedOutboundBuffer`。
4. 启动一个 Turn Worker；成功后才允许 offset 前进。
5. Worker 内启动 Producer 执行 `MessageTurnService.process`，当前 Worker 消费 Buffer 并交给 Renderer。
6. 无论成功、失败或启动异常，finally 移除 Conversation、释放许可并完成 Join。

若 1/2 失败，向已授权 Chat 发送固定 `SESSION_BUSY`，不调用模型。忙提示失败只影响 Adapter Delivery 计数，不占用 Turn。

## 8. 显式取消与 Buffer 扩展

`BoundedOutboundBuffer` 新增：

```java
public boolean requestCancellation()
```

它只调用内部 `TurnCancellationSource.cancel(REQUESTED)`，不改变 Buffer `OPEN` 状态、不清空队列。这样 Producer 可以观察取消，并继续发布唯一 `TURN_CANCELLED`。已有 `disconnect()`、`shutdown()` 和背压语义不变。

`/cancel`/`/stop` 通过 Conversation 查找 `ActiveTurn` 并调用该方法。若关闭已先写入 `SHUTDOWN`，请求取消返回 false，First Writer Wins 保持不变。

## 9. Outbound Consumer 与渲染

### 9.1 消费

每个 Turn 使用已有 `BoundedOutboundBuffer`；Consumer 以正的 Poll Timeout 读取，直到 Renderer 看到终态。Producer 退出但没有终态时视为内部故障；Consumer Disconnect Buffer 并记录稳定状态。

Renderer 自己再用绑定当前 Inbound 的 `OutboundSequenceValidator` 验证，形成 Adapter 边界的第二道 Fail Closed。它不信任错误 Turn、Session、Route 或 Sequence。

### 9.2 终态合并

R6.3 不做 Telegram 实时消息编辑：

- Started/Delta 只更新本地协议状态；不保留超出 R6.1 预算的副本。
- Completed 直接使用终态完整文本，不依赖 Delta 拼接。
- Cancelled/Failed 只生成固定安全文本。

因此 Tool Loop 早期 Delta 与最终回答不同不会造成 Telegram 消息残留；CLI 仍可实时预览，两者最终权威文本一致。

### 9.3 分片与发送

`TelegramTextChunker` 按最多 4,000 UTF-16 单位切分，并在高/低 Surrogate 边界前回退。发送顺序固定，不使用并行请求。

`TelegramTerminalRenderer` 对每片调用 Delivery Policy：

- 首次成功：继续下一片。
- 明确 429 且 `retryAfter<=maxRetryAfter`：用可注入 `ChannelSleeper` 等待后重试一次。
- 其他失败：停止后续片段，抛稳定 Delivery Exception。

Adapter 捕获 Delivery Exception：若 Turn 仍在执行则 `buffer.disconnect()`；若已终态则只记录外部投递失败。它不尝试伪造第二终态，也不回滚已提交 Conversation。

## 10. 网络恢复与状态

Poll 成功把 Adapter 状态设为 `RUNNING` 并清零连续失败。可安全重试的 Poll 失败：

1. 状态变为 `DEGRADED`。
2. 通过可注入 Sleeper 等待固定 Backoff。
3. 在相同 offset 重试，最多连续 3 次。
4. 超出预算后状态变为 `FAILED`，调用 `stopAccepting()`，以 `CHANNEL_DISCONNECTED` 断开活动 Buffer，并退出 Poll Worker。

401/403/404 或协议损坏直接永久失败。Host 不自动重启 FAILED Adapter；由运维修复配置后重启应用。该选择避免无限后台重试和不可观察的资源消耗。

## 11. Shutdown 竞态

`TelegramChannelAdapter.close()`：

1. CAS `accepting=false`。
2. 中断 Poll Thread 以取消阻塞 `HttpClient.send`。
3. 对活动 Buffer 调用 `shutdown()`；First Writer Wins 保留更早取消原因。
4. 在总 Shutdown Deadline 内 Join Poll 和 Turn Worker。
5. 超时后中断剩余 Worker，再做一次有界 Join；仍存活则抛稳定关闭异常。
6. 清空只包含对象引用的活动 Map，验证 Semaphore 许可全部返回。

Turn 完成/取消、Poll 失败和 Shutdown 通过原子状态与 Buffer Cancellation 竞争，不以 `sleep` 作为正确性条件。

## 12. 测试设计

### 12.1 单元测试

- `ChannelHostTest`：启动隔离、反向关闭、幂等和安全快照。
- `BoundedOutboundBufferTest`：请求取消保持 Buffer 开放、唯一取消终态和 First Writer Wins。
- `TelegramPropertiesTest`：Disabled/Enabled、Allowlist、预算关系和 Secret 不在属性中。
- `TelegramUpdateMapperTest`：可信映射、私聊/Allowlist/命令/未知字段边界。
- `TelegramTextChunkerTest`：边界、Surrogate、换行和超长文本。
- `TelegramTerminalRendererTest`：Delta 合并、权威纠正、稳定错误和 429 单次重试。
- `TelegramChannelAdapterTest`：Offset、去重、会话占用、全局许可、取消、启动失败和 Shutdown。

### 12.2 HTTP 集成测试

`JdkTelegramBotApiIT` 使用本地 JDK `HttpServer` 验证：

- 请求方法、UTF-8 JSON、`allowed_updates`、offset、limit 和 Timeout。
- Poll/Send 成功解析。
- 401、429/`retry_after`、5xx、损坏 JSON、`ok=false`、正文超限和中断。
- Fake Token 不出现在异常和测试日志断言中。

`TelegramChannelIT` 使用 Fake Bot API + Fake Chat：

- 合法私聊 -> Started/Delta/Completed -> 只发送权威终态。
- Tool/MCP 风格多轮 Delta 不泄漏结构化数据。
- `/cancel` 取消阻塞中的目标 Turn，不影响另一 Chat。
- 断开/重试耗尽取消活动 Turn。
- 关闭后线程、许可、活动 Map 和未消费 Buffer 为零。

### 12.3 Fixture 与 Profile

`telegram-channel-v1.json` 由生产 Mapper、Chunker、Renderer 和 Lifecycle 状态机消费，并加入 Manifest。`compat` 不运行 Python；`failure` 使用闩锁/Barrier/Fake Server，不依赖墙钟竞态或真实 Telegram。

## 13. 非目标与后续切片

- 消息编辑式 Delta、Markdown/HTML、附件和群组在独立 Contract 中实现。
- R6.4 冻结前不持久化 Update Offset、Inbox 或 Delivery。
- Webhook 需要公网 TLS、来源认证和重放防护，必须新 ADR。
- 真实 Smoke 需要专用 Bot、测试 User/Chat Allowlist、Token 注入/撤销和数据删除说明；不属于本设计的默认实施授权。

## 14. 实现验证

2026-07-16，设计中的 Channel Host、Bot API Client、可信 Mapper、终态 Renderer、Adapter 生命周期、
默认关闭装配和 Java-owned Fixture 已全部落地。聚焦 Fixture 验收 25 个、Loopback 纵向集成 4 个；
最终默认、`failure`、`compat` 分别通过 455、119、519 个测试。依赖与安全审计确认
Kernel/Application 没有 Telegram、HTTP、Jackson 或 Spring 生产依赖，Disabled/CLI/配置检查不读取
Token、不创建 Telegram 网络或 Worker，关闭后无活动 Turn、许可、线程和测试 Server 残留。

这些证据只完成离线设计验收。部署继续保持 `DISABLED`，真实 Telegram Smoke 仍受独立授权门禁约束。
