# Provider Streaming 与本地 CLI 设计

- 状态：已实现并验证
- 日期：2026-07-15
- 阶段：R6.2
- 批准记录：用户已批准并要求完整实现 R6 总体计划
- Contract：[Provider Streaming 与本地 CLI 契约](../contracts/provider-streaming-cli.md)
- ADR：[ADR-0008：使用项目自有同步流式观察协议桥接 Provider](../adr/0008-use-project-owned-synchronous-stream-observer.md)
- 实施计划：[Provider Streaming 与本地 CLI 工作计划](../plans/2026-07-15-provider-streaming-cli-implementation.md)

## 1. 设计目标

在不改变现有同步 HTTP 契约的前提下，让模型的真实文本 Chunk 经过 Application Tool Loop、R6.1 Message Runtime 和有界 Buffer 到达本地 CLI。完成、取消、失败、Tool/MCP 和 SQLite 仍使用现有控制权与安全边界。

## 2. 模块关系

```text
agent-kernel
  CancellationSignal
  ChatModelStreamObserver
  ChatModelPort streaming overload

agent-application
  TurnCancellation extends CancellationSignal
  ChatProgressListener + StreamingBudget
  ToolLoop -> ChatModelPort
  ChatService -> ToolLoop
  MessageTurnService -> OutboundMessageSink

adapter-spring-ai
  ChatModel.stream(Prompt)
  -> bounded sequential Chunk consumption
  -> text callback + aggregate ChatModelResponse

agent-bootstrap
  CliProperties
  LocalCliRunner
  explicit CLI bootstrap path
```

依赖方向保持 Adapter/Bootstrap 指向 Kernel/Application；Kernel/Application 不引用 Reactor、Spring AI、终端或 Spring 类型。

## 3. Kernel 协议

### 3.1 `CancellationSignal`

接口包含：

- `isCancellationRequested()`。
- `onCancellation(Runnable)`，返回可关闭 Registration。
- `none()`。

回调至多一次，晚注册立即执行。Kernel 不保存取消原因；Application `TurnCancellation` 继续提供 R6.1 原因。

### 3.2 `ChatModelStreamObserver`

单方法 `onContentDelta(String)`。实现可以因预算、Sink、取消或下游故障抛出 RuntimeException；Adapter 必须停止 Subscription 并原样保留项目异常类型。

### 3.3 `ChatModelPort`

保留：

```java
ChatModelResponse generate(ChatModelRequest request);
```

新增默认重载：

```java
default ChatModelResponse generate(
    ChatModelRequest request,
    ChatModelStreamObserver observer,
    CancellationSignal cancellation)
```

默认实现预检查取消后调用原方法，不产生 Delta。接口仍是 `@FunctionalInterface`。

## 4. Application 设计

### 4.1 `ChatProgressListener`

Application 的单方法文本进度接口。`ChatUseCase` 增加默认流式重载，现有实现默认退化为非流式。

### 4.2 `StreamingBudget`

每个 Turn 独立实例，顺序统计：

- Delta 事件数。
- 累计 Unicode Code Point。

输入为空拒绝；超过事件或字符上限抛出稳定 `ModelStreamLimitExceededException`。不截断、不继续提交。

### 4.3 `ToolLoop`

每次 `model.generate` 都传递同一个进度 Listener 和 Cancellation Signal。Observer 顺序：

1. 检查取消。
2. 扣减流式预算。
3. 调用 Application 进度 Listener。

模型返回 Tool Call 后沿用现有审批、执行、结果回送和迭代上限。Thinking/Tool Fragment 不会到达 Listener。

### 4.4 `ChatService`

新增流式重载，但会话 Gate、历史、Memory、Tool Loop、取消检查和 SQLite 原子提交不改变。最终 `ChatResult` 仍只包含完整 Assistant Message。

### 4.5 `MessageTurnService`

调用 Chat 流式重载。每个进度回调通过同一个 `OutboundMessageSequence.delta()` 生成严格序号并发布到 Sink。完成或错误继续使用 R6.1 唯一终态。

若 Delta 发布抛出 `OutboundDeliveryException`，异常跨模型 Adapter 原样传播，终止 Provider；Service 不再尝试发布第二终态。

## 5. Spring AI Adapter

### 5.1 Prompt 与 Options

同步和流式共用同一个 Prompt 构造路径。存在工具时从 `chatModel.getOptions()` 取得实际 `ToolCallingChatOptions`，通过 `mutate()` 注入 Schema-only Callback，保留模型与 Provider 配置。

### 5.2 Flux 桥接

Adapter 对 `chatModel.stream(prompt)`：

- 应用空闲 `timeout`。
- 使用顺序、受控需求消费 Chunk。
- 对每个非空文本立即调用 Observer。
- 有界累积文本和 Tool Call。
- 监听 Cancellation Signal 并 dispose Subscription。
- 完成后构造一个项目 `ChatModelResponse`。

取消、Observer 异常和 Provider 错误竞争同一个完成 Future；First Terminal Wins。完成 Future 后迟到 Chunk 被忽略。

### 5.3 错误

- Reactor/Socket/HTTP Timeout -> `ModelTimeoutException`。
- 损坏 Chunk、重复/非法 Tool Call -> `InvalidModelResponseException`。
- 取消 -> `TurnCancelledException`。
- 其他 Provider 错误 -> `ModelInvocationException`。
- 已是项目 RuntimeException（例如 Sink/预算异常）不得二次包装为 Provider 错误。

## 6. CLI 设计

### 6.1 `CliProperties`

配置：

- `session-id` 与 `conversation-id`。
- Buffer Capacity。
- Publish Timeout 与 Poll Timeout。
- Delta 事件和累计字符上限由 Streaming Settings 提供。

字段有严格长度和范围，默认只面向本地单用户。普通用户输入不能修改这些值。

### 6.2 `LocalCliRunner`

依赖注入：

- `MessageTurnService`。
- `CliProperties`。
- `Clock`、ID 生成器、输入与输出抽象。
- 可控 Virtual Thread Starter，便于确定性测试启动失败和关闭竞态。

每个非空输入行：

1. 构造 `InboundMessage` 和本 Turn `BoundedOutboundBuffer`。
2. 在一个 Virtual Thread 中执行 Message Turn。
3. 当前 CLI 线程顺序 `poll()` 并渲染事件。
4. 终态消费完成后 Join Producer，再读取下一行。
5. 输出故障时 Disconnect Buffer；关闭时 Shutdown 活动 Buffer。

不并发读取多个用户 Turn，不后台保存输入，不支持正文内 Session Override。

### 6.3 启动路径

`NameiAgentApplication` 在显式 CLI 参数下使用 `WebApplicationType.NONE` 启动 Spring Context，取得 CLI Runner 并执行；普通启动保持现有 Web 行为。配置检查入口优先级不变。

## 7. 测试设计

- Kernel：Golden 与 Port 兼容、预取消、文本不变量。
- Application：多迭代 Tool Loop、预算、取消、Delta/终态顺序和提交隔离。
- Spring AI：Fake Flux 单元测试与 OpenAI-compatible SSE HTTP Stub 集成测试。
- Bootstrap：纯 CLI Runner 测试、无 Web CLI Context、普通 Web 回归和关闭竞态。
- Failure Profile：Idle Timeout、损坏流、取消/完成、背压/断开和输出故障。
- Compat Profile：Java-owned Streaming/CLI Fixture，不运行 Python 或真实 Provider。

## 8. 非目标

不新增 Maven 模块、数据库表、网络渠道、Dashboard API、终端 UI 框架、Signal Library、消息中间件或 Provider 自动重试策略。

## 9. 实现结果

设计已按模块边界落地。Spring AI Reactor 流由 Adapter 内部同步桥接，Application 负责跨 Tool Loop 的 Delta 预算、取消和最终提交，CLI 只做受信路由与有界输入输出。为补足 Spring AI Reactor Subscription 取消不能稳定关闭底层 OpenAI HTTP 调用的缺口，Adapter 使用请求关联的 OkHttp Cancellation Registry：内部关联 Header 在 Transport 层定位目标 Call，并在发送给 Provider 前移除，因此并发流只取消目标连接且不会把内部标识泄漏给上游。

实现期间的全量门禁还发现轻量 Spring Context 未加载 YAML 时，`CliProperties` 会得到空值/零值。最终在配置绑定层用与 YAML 一致的 `@DefaultValue` 固定 `cli:local`、`local`、`32`、`2s` 和 `100ms`，同时保留显式非法配置的启动拒绝。

最终离线证据为：默认 363 个测试（345 单元、18 集成）、`failure` 99 个（96 单元、3 集成）、`compat` 402 个（383 单元、19 集成），全部零失败、零错误、零跳过；Kernel 生产依赖和安全审计通过。真实外部 Provider/渠道、Secret、费用和用户工作区没有执行，也不由本设计自动授权。
