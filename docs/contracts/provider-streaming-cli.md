# Provider Streaming 与本地 CLI 契约

- 状态：已批准，实施中
- 契约版本：1
- 日期：2026-07-15
- 阶段：R6.2
- 批准记录：用户已批准并要求完整实现 R6 总体计划；真实 Provider 网络、Secret 和付费 Smoke 保留独立授权门禁
- 前置契约：[版本化渠道消息与流式运行时契约](versioned-channel-message-runtime.md)
- 关联 ADR：[ADR-0008：使用项目自有同步流式观察协议桥接 Provider](../adr/0008-use-project-owned-synchronous-stream-observer.md)
- 关联设计：[Provider Streaming 与本地 CLI 设计](../specs/2026-07-15-provider-streaming-cli-design.md)
- 实施计划：[Provider Streaming 与本地 CLI 工作计划](../plans/2026-07-15-provider-streaming-cli-implementation.md)

## 1. 目的

本契约固定 Provider Streaming、Application Tool Loop、R6.1 Channel Message Runtime 与本地 CLI 之间的最小边界。流式能力不得让 Reactor、Spring AI、Provider SDK 或终端框架取得 Agent Loop 控制权。

## 2. 范围

R6.2 包含：

- 纯 JDK 的模型流式观察与取消协议。
- `ChatModelPort` 的兼容流式调用入口。
- Tool Loop、Chat Service 和 Message Turn 的 Delta 传播。
- Spring AI `ChatModel.stream(Prompt)` 到项目协议的 Adapter。
- 有界、单进程、文本型本地 CLI。
- 离线 Fake、HTTP Stub、故障、取消、背压、提交和 CLI 验收。

R6.2 不包含：

- Telegram、QQ、SSE/WebSocket 服务端或 Dashboard。
- Provider Thinking/Reasoning 的渠道展示。
- Tool Arguments、Tool Result 或结构化 Tool Call 的渠道展示。
- Session/Route Override、附件、Markdown 富渲染或终端 UI 框架。
- 持久 Inbox/Outbox、自动重放、新数据库表或消息中间件。
- 默认 CI 中的真实 Provider、真实 Key、真实费用或真实 Workspace。

## 3. 模型流式协议

### 3.1 兼容入口

`ChatModelPort` 保持单抽象方法，因此现有 Lambda、Fake 和非流式 Adapter 继续有效。新增流式重载接受：

- `ChatModelRequest`。
- 只接收文本片段的 `ChatModelStreamObserver`。
- 纯 JDK `CancellationSignal`。

默认实现检查预取消后调用原非流式 `generate(request)`，不制造伪 Delta。支持真实流式的 Adapter 显式覆盖重载。

### 3.2 Delta

- Delta 必须非空，但仅由空白组成的 Provider 文本片段可以保留。
- Delta 保留 Provider 给出的原始文本，不做 `strip()`、Markdown 解析或 Thinking 提取。
- Thinking/Reasoning 永不调用文本 Delta Observer。
- Tool Call Fragment 由 Adapter 有界聚合，不作为 Delta 暴露。
- Delta 是暂定预览；跨 Tool Loop 迭代时，早期文本可能不属于最终回答。
- Consumer 必须以最终 `TURN_COMPLETED.content` 为权威快照。

### 3.3 完成

- 每次模型调用最终返回一个完整 `ChatModelResponse`，包含聚合文本和完整 Tool Call。
- 无文本但包含 Tool Call 合法；无文本且无 Tool Call 为无效响应。
- Tool Loop 只有在最终模型响应不含 Tool Call 时结束。
- 最终 Chat 文本经过现有领域规范化并只提交一次。

### 3.4 预算

Application 对一个 Agent Turn 的公开 Delta 执行：

- 最大事件数。
- 最大累计 Unicode Code Point 数。

Adapter 继续执行 Tool Arguments UTF-8 字节上限，并对 Provider 流执行空闲超时。任一上限触发稳定失败，不能静默截断后继续提交。

## 4. 取消与背压

- `CancellationSignal` 只暴露查询和回调注册，不暴露 Channel 实现。
- 预取消不得启动 Provider 请求。
- 运行中取消必须释放 Subscription；迟到事件不得再调用 Observer。
- Channel Disconnect、Backpressure、Shutdown 和用户请求沿用 R6.1 First Writer Wins 原因。
- Observer 因 Sink 失败抛出异常时，Provider Subscription 必须终止，Message Turn 不再尝试另一终态。
- 线程中断恢复中断标记并映射为取消或稳定模型失败，不吞掉中断。

## 5. Tool Loop 语义

- 每一次模型迭代都可以产生公开文本 Delta。
- 一旦 Provider 给出 Tool Call，Adapter 只聚合 Tool Call，不暴露 Arguments Fragment。
- 已经公开的文本 Delta不回滚；它仍是暂定预览。
- Tool 执行和 Tool Result 回送沿用现有 Application 编排。
- 最终 `TURN_COMPLETED` 只包含最后一次无 Tool Call 模型响应的完整文本。
- 工具中间消息和 Delta 不写入 SQLite；成功仍只提交最终 `user/assistant`。

## 6. Spring AI Adapter

- Reactor 只存在于 `adapter-spring-ai`。
- 使用当前 `ChatModel.stream(Prompt)`；不把 `Flux` 暴露给 Kernel/Application。
- 继续从 `chatModel.getOptions()` 取得实际 Provider Options，并通过 `ToolCallingChatOptions.mutate()` 注入 Schema-only Callback。
- 使用有界需求或等价背压策略顺序消费 Chunk。
- 聚合文本和完整 Tool Call 后构造项目 `ChatModelResponse`。
- Spring AI/Provider 的异常正文不进入项目稳定消息或 Channel。

## 7. 本地 CLI

### 7.1 启动

- CLI 通过显式命令行模式启动；默认 Web 启动行为不改变。
- CLI 模式不启动 HTTP Server。
- CLI 仍会使用 Java 专用 Workspace 和现有 SQLite；它不是无副作用配置检查。
- MCP、Memory 和 Tool Mode 继续由现有配置决定，默认模板保持 `DISABLED`。

### 7.2 入站

- stdin 按有界 UTF-8 文本行读取；空白行忽略。
- Adapter 生成新的 Message ID 和 Turn ID。
- `channel` 固定为 `cli`，Sender 固定为安全本地标识。
- Session ID 来自受信配置，不能由普通消息正文覆盖。
- Route 的 Conversation ID 由受信 CLI 配置计算。

### 7.3 出站

- CLI 顺序消费 R6.1 Buffer。
- `TURN_STARTED` 不展示正文。
- Delta 原样预览。
- 若预览拼接与权威完成快照相同，只补终止换行；若不同，另起一行打印权威完整结果。
- Cancelled/Failed 只显示稳定码，不显示原始异常。
- stdout 故障转换为 `CHANNEL_DISCONNECTED` 并取消活动 Turn。

### 7.4 生命周期

- 初始版本一次只处理一个 stdin Turn。
- EOF 在当前 Turn 结束后正常退出。
- JVM/Spring Shutdown 会以 `SHUTDOWN` 取消活动 Turn。
- 不安装平台特定 Signal Handler；`Ctrl+C` 复用 JVM/Spring Shutdown Hook。

## 8. Python 兼容与安全差异

Python 仅作为行为参考。Java 有意不复制：

- `asyncio.Queue()` 无界入站/出站队列。
- `thinking_delta` 渠道事件。
- 消息 Metadata 中的 `session_key_override`、`as_channel` 或 `as_chat_id`。
- 发送失败后无条件重放整个消息或 Turn。
- 日志中的用户正文预览、原始 Session 或 Provider 错误正文。

这些差异由 R6.1/R6.2 Contract 和 ADR 授权，不属于遗漏。

## 9. 验收标准

- 原非流式 `ChatModelPort` Fake、HTTP Chat 和 Tool Loop 行为保持兼容。
- Fake Streaming 覆盖多 Delta、无 Delta、Tool Call、取消、超时、错误和迟到事件。
- Spring AI HTTP Stub 证明真实流 Chunk、Options、Tool Schema 和 Tool Result 回送。
- Message Turn 严格产生 Started、Delta 和一个终态。
- CLI 普通回答、Tool/MCP 最终回答、取消、失败、背压和输出故障均有确定性测试。
- SQLite 只提交完整成功轮次。
- 默认、`failure`、`compat`、Kernel 依赖和安全门禁通过。
