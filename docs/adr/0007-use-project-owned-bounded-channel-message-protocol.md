# ADR-0007：采用项目自有的有界渠道消息协议

- 状态：已接受
- 日期：2026-07-15
- 批准记录：用户要求从 R6 的版本化 Message Contract Fixture 开始连续 TDD 实现
- 关联 Contract：[版本化渠道消息与流式运行时契约](../contracts/versioned-channel-message-runtime.md)
- 关联 Spec：[版本化渠道消息 Contract Runtime 设计](../specs/2026-07-15-versioned-channel-message-runtime-design.md)

## 背景

Java 当前只有同步 HTTP `ChatRequest/ChatResponse`，内部 `TurnLifecycleEvent` 仅供观察。Python 有 `InboundMessage`、`OutboundMessage`、`StreamDeltaReady` 和无界 `asyncio.Queue`，但没有版本字段、严格序号、唯一终态或有界背压；直接复制会把渠道路由、业务编排和不受控队列带入 Java Core。

R6 需要先建立 CLI，再接真实渠道和 Dashboard 流式输出。若每个 Adapter 自行定义 Delta、完成、取消和错误，之后无法可靠处理断开、乱序、重试和兼容升级。

## 决策

项目在 `agent-kernel` 定义纯 JDK、版本化、不可变的 `InboundMessage` / `OutboundMessage` 和序号状态机；`agent-application` 定义取消感知的 Turn 投影与有界缓冲。Channel Adapter 只能消费这些项目类型，不得把 Spring、Reactor、Provider SDK、MCP SDK 或渠道 SDK 类型带入 Kernel/Application。

版本 1 采用：

- 每 Turn 独立序列，`TURN_STARTED` 从 `sequence=0` 开始。
- `CONTENT_DELTA` 只作预览，`TURN_COMPLETED` 携带权威完整回答。
- `COMPLETED/CANCELLED/FAILED` 三选一且只能出现一次。
- `ArrayBlockingQueue` 风格的有界、Deadline 发布，不静默丢弃。
- Channel 断开、背压和关闭复用现有 `TurnCancellation`。
- 安全错误白名单，不暴露异常或消息正文。
- 不引入 Reactor、Kafka、Redis、WebFlux 或新的部署单元。

## 备选方案

| 方案 | 优点 | 拒绝原因 |
| --- | --- | --- |
| 直接复制 Python MessageBus | 与当前 Python 结构接近 | 队列无界，缺版本、序号、终态和背压语义 |
| 使用 Reactor `Flux` | 流式与背压 API 成熟 | 违反 Kernel/Application 依赖边界，并会让框架取得运行时语义所有权 |
| 只保留同步 `ChatResponse` | 改动最小 | 无法支持 CLI 取消、真实流式和多渠道一致行为 |
| 每个 Channel 自定义事件 | Adapter 简单 | 产生多套不兼容终态、错误与断线语义 |
| 引入消息中间件 | 跨进程与持久化能力强 | 当前单机纵向切片不需要，显著扩大部署和一致性范围 |

## 后果

- CLI、HTTP 流式和未来渠道共享同一组可执行 Fixture 与状态机。
- Kernel 继续没有生产第三方依赖；背压由项目代码显式控制。
- 版本 1 不提供跨进程恢复、自动重放或 Exactly Once；这些能力必须通过新 ADR 和数据 Contract 引入。
- Provider 真流式仍需 Adapter 能力和聚焦测试；R6.1 只建立可承载 Delta 的协议，不伪造流式输出。
- Channel Delivery 与 Conversation Commit 是不同边界；Commit 后断线不回滚持久化，也不自动重放。
