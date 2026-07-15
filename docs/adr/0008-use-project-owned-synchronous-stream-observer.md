# ADR-0008：使用项目自有同步流式观察协议桥接 Provider

- 状态：已接受
- 日期：2026-07-15
- 阶段：R6.2
- 批准记录：用户已批准并要求完整实现 R6 总体计划
- 关联 Contract：[Provider Streaming 与本地 CLI 契约](../contracts/provider-streaming-cli.md)
- 关联 Spec：[Provider Streaming 与本地 CLI 设计](../specs/2026-07-15-provider-streaming-cli-design.md)

## 背景

当前 `ChatModelPort` 是同步、纯 JDK 的单抽象方法，Tool Loop 由 Application 控制。Spring AI 2.0.0 的 `ChatModel` 同时提供同步 `call` 和 Reactor `Flux<ChatResponse>` 的 `stream`。若直接把 `Flux` 引入 Port，Kernel/Application 会依赖 Reactor，Provider SDK 将反向控制循环、取消和提交语义。

Python 使用异步回调发布 `content_delta`/`thinking_delta`，同时在 Bus 中使用无界 `asyncio.Queue`。直接复制会破坏 Java 已冻结的有界背压、Thinking 隔离和唯一终态。

## 决策

1. `ChatModelPort` 保持原单抽象方法，并新增有默认实现的流式重载。
2. Kernel 定义纯 JDK `ChatModelStreamObserver` 与 `CancellationSignal`。
3. Application 的 `TurnCancellation` 扩展 Kernel Cancellation Signal，并保留渠道原因。
4. Application 同步调用模型；流式 Adapter 在调用期间顺序回调文本 Delta，完成后返回完整 `ChatModelResponse`。
5. `adapter-spring-ai` 在内部把 `Flux` 桥接为上述同步协议，负责 Subscription、空闲超时、异常映射和 Tool Call 聚合。
6. R6.1 有界 Buffer 继续负责 Channel Producer/Consumer 背压；不新增全局事件总线或无界队列。
7. 最终完成快照权威，Delta 只作预览；Thinking 和 Tool 结构不进入 Channel。

## 结果

优点：

- Kernel/Application 继续无 Reactor 和 Spring AI 依赖。
- 现有 Lambda/Fake/非流式调用保持源码兼容。
- Tool Loop、取消、背压和 SQLite 提交仍由项目控制。
- Adapter 可以立即释放真实 Provider Subscription。

代价：

- 模型调用线程在流结束前保持阻塞。
- Adapter 需要谨慎桥接同步等待、Subscription 和取消竞态。
- Tool Call 出现在后期时，已公开的早期文本只能作为暂定预览，由权威完成快照纠正。

## 被拒绝方案

- **Port 直接返回 `Flux`**：违反核心模块无 Reactor 约束。
- **Application 只消费完整响应后再拆成 Delta**：不是真实 Provider Streaming。
- **复制 Python 无界 Event Bus**：没有背压和可靠资源上限。
- **把 Thinking/Tool Fragment 当普通 Delta**：泄漏敏感推理和结构化工具数据。
- **另建 CLI 私有协议**：导致 CLI 与未来真实渠道行为分叉。
