# 版本化渠道消息 Contract Runtime 设计

- 状态：已实现并验证
- 日期：2026-07-15
- 阶段：R6.1
- 批准记录：用户要求完成 MCP PR 与远程 CI 后，从 R6 的版本化 Message Contract Fixture 开始连续 TDD 实现
- Contract：[版本化渠道消息与流式运行时契约](../contracts/versioned-channel-message-runtime.md)
- ADR：[ADR-0007：采用项目自有的有界渠道消息协议](../adr/0007-use-project-owned-bounded-channel-message-protocol.md)

## 1. 目标与非目标

目标是建立一个可由 CLI、HTTP 和未来真实渠道共同消费的 Java 消息运行时边界：入站文本被规范化一次，现有 Chat/Memory/Tool/MCP 闭环只执行一次，过程被投影为严格有序且安全的出站事件。

本阶段不实现真实 Provider Streaming、CLI 读取循环、SSE/WebSocket、Telegram、附件、主动消息或持久消息队列。它们必须复用本设计，而不能先创造另一套临时消息格式。

## 2. 模块与依赖

```text
future Channel Adapter
        |
        v
agent-application  ----> agent-kernel
        |                    |
        |                    +-- versioned message values
        |                    +-- sequence state machine
        +-- MessageTurnService
        +-- BoundedOutboundBuffer
        +-- cancellation / safe failure mapping
```

- `agent-kernel`：值对象、枚举、工厂和严格状态机，只依赖 JDK。
- `agent-application`：调用 `ChatUseCase`、传播取消、错误归一化和有界缓冲。
- `agent-bootstrap`：本阶段只更新 `SafeChatUseCase` 以保留取消 Token；不新增 Channel Bean。
- 不新增 Maven 模块、数据库或外部依赖。

## 3. Kernel 类型

### 3.1 `MessageRoute`

保存 `channel` 和 `conversationId`。构造时规范化外层空白、验证长度、Channel 格式和控制字符。对象不可变且不提供包含路由原文的自定义日志。

### 3.2 `InboundMessage`

保存 Contract 版本、三个关联标识、Route、Sender、文本和来源时间。构造函数是唯一不变量入口；不接受 Map Metadata。`sessionId` 已由可信 Adapter 计算，Application 不再读取模型或消息正文中的 Override。

### 3.3 `OutboundMessage`

使用一个不可变 Envelope 和静态工厂表达五种类型。构造函数验证类型与 `content/code/retryable` 的互斥组合，防止 Adapter 构造“失败但带回答”或“完成但带错误码”的消息。

### 3.4 `OutboundMessageSequence`

每个实例绑定一个 Inbound 身份并持有 `NEW/ACTIVE/TERMINAL` 状态：

- `started()` 只允许一次并生成序号 0。
- `delta()` 只在 ACTIVE 生成下一序号。
- 三个 terminal 方法竞争同一同步状态转换。
- 终态后所有调用失败。

状态机不发布消息、不创建线程、不调用外部资源。

## 4. Application 类型

### 4.1 取消原因

`TurnCancellation` 增加只读 `reason()`，默认 `REQUESTED`；`TurnCancellationSource` 以 First Writer Wins 记录原因。重复取消返回 `false`，不能覆盖第一原因。回调继续至多执行一次且异常隔离。

### 4.2 `ChatUseCase`

保留现有单参数便利方法，同时新增取消感知调用。`ChatService` 已有实际取消入口；`SafeChatUseCase` 必须把 Token 原样传递，不能在观察包装层丢失取消。

### 4.3 `BoundedOutboundBuffer`

每个实例只承载一个 Turn：

- 固定容量和发布 Deadline。
- 使用严格序号 Validator 防止错误 Publisher 绕过 Kernel Sequencer。
- 发布成功后才推进序号状态。
- Deadline 到期记录 `BACKPRESSURE_EXCEEDED` 并取消。
- `disconnect()` 记录 `CHANNEL_DISCONNECTED`、拒绝后续发布并清空不再可见的预览。
- Consumer 使用有界 `poll`；无后台线程。

### 4.4 `MessageTurnService`

```text
InboundMessage
  -> publish TURN_STARTED
  -> ChatUseCase.chat(ChatCommand, cancellation)
  -> success: publish TURN_COMPLETED(full content)
  -> TurnCancelledException: publish TURN_CANCELLED(reason)
  -> other RuntimeException: publish TURN_FAILED(safe code)
```

它不解析 Prompt、不调用模型 SDK、不执行 Tool、不写数据库。所有业务继续由现有 Chat Application 负责。Sink 失败属于 Delivery 边界，直接向调用方抛稳定异常，不再尝试向已满或断开的 Sink 写第二个终态。

## 5. 错误映射

Application 使用显式类型映射，不检查异常 Message：

| Java 失败 | Channel Code |
| --- | --- |
| `SessionLockTimeoutException` | `SESSION_BUSY` |
| `ModelTimeoutException` | `MODEL_TIMEOUT` |
| `ModelInvocationException` | `MODEL_UNAVAILABLE` |
| `InvalidModelResponseException` | `INVALID_MODEL_RESPONSE` |
| Tool Call/Loop 上限 | `TURN_LIMIT_EXCEEDED` |
| `MemoryContextUnavailableException` | `CONTEXT_UNAVAILABLE` |
| `ApprovalUnavailableException` | `APPROVAL_UNAVAILABLE` |
| `SideEffectStateUnknownException` | `SIDE_EFFECT_STATE_UNKNOWN` |
| Kernel `SessionPersistenceException` | `PERSISTENCE_FAILED` |
| 其他 Runtime Exception | `INTERNAL_ERROR` |

SQLite Adapter 的异常改为继承 Kernel `SessionPersistenceException`，不把 Adapter 类型反向引入 Application。

## 6. 并发与竞态

- 同一 Session 仍由现有 `SessionExecutionGate` 串行。
- Outbound Sequence 只保护单 Turn；不同 Turn 不共享锁。
- `TurnCancellationSource` 的原因和回调使用原子 First Writer Wins。
- Completion/Cancel/Failure 通过 Sequencer 唯一终态同步点竞争。
- Buffer 发布在单实例锁内完成“验证、offer、推进”，Consumer 只操作线程安全队列。
- Backpressure 等待时间不超过正 Deadline；中断恢复线程中断标记并映射为稳定投递失败。

## 7. Fixture

Fixture 为 Java-owned `formatVersion=1` 文档，Contract Evidence 指向本 Contract、Spec 和 ADR。Kernel Golden Test 读取 Fixture，用生产构造器和状态机执行每个 Case；它不是只检查 JSON 字段存在的快照测试。

Fixture 不包含真实 Session、用户正文、Token、路径或外部渠道标识。示例值均为固定测试数据。

## 8. 验收标准

- Fixture Hash 和 Case ID 进入统一 Manifest。
- Kernel 类型拒绝未知版本、非法标识、错误 Payload 和不合法顺序。
- 并发终态竞争只成功一次。
- 取消原因 First Writer Wins，Safe Decorator 不丢 Token。
- Buffer 不丢事件；满载和断开均取消并安全失败。
- MessageTurnService 对成功、取消和全部稳定错误类只发一个终态。
- 默认、`failure`、`compat` 门禁通过；Kernel 依赖树仍无生产依赖。
- 文档明确 R6.1 不等于真实流式或 CLI 已完成。
