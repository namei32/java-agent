# ADR-0033：只在单个 Tool Loop 内回放有界 Provider reasoning

- 状态：已接受
- 日期：2026-07-19
- 阶段：R10-P2
- 关联 Contract：[R10-P2 reasoning/Tool continuation](../contracts/r10-reasoning-tool-continuation.md)

## 背景

Akashic 的 DeepSeek 策略从响应提取 `reasoning_content`，并在含 Tool Call 的 assistant 消息续轮中回传它。没有这项字段，
某些 thinking Tool 链路无法继续。Java P1 刻意在任意 Tool Schema 时抑制 thinking，因为此前没有明确的数据保留边界。

Spring AI 2.0 的 OpenAI Adapter 已将 Provider 的 `reasoning_content` 放入 `AssistantMessage` metadata 的
`reasoningContent`，并只在该 metadata 存在时将它序列化回下一条 assistant message。因此 Java 可以使用这个受支持的
边界，而不需要把 Provider 原文放进 Session 或自建 JSON 协议。

## 决策

新增严格 `agent.model.reasoning-continuation.mode`：默认 `DISABLED`；仅显式 `SAFE_LOCAL` 可启用。启用后也只针对
`DEEPSEEK` Profile 的单一 `ToolLoop`：Adapter 从 assistant metadata（或严格 `<think>...</think>` 分段）提取最多
16,384 code points 的 reasoning；只有同一响应含 Tool Call 时，`ToolLoop` 才在它紧随的内存 assistant ToolCall message
上携带该值。Spring AI 只在下一模型请求序列化它。Tool result 后的最终文本、Session append、SQLite、日志、生命周期、
Channel、HTTP/CLI 和观察 Port 都没有此字段。

超长、缺失、空白、取消、失败、非 DeepSeek、最终文本或 Tool Schema 但未开启 `SAFE_LOCAL` 均不回放。任何 Tool 执行后
的错误仍遵循 P3 的零恢复/零重放规则。

## 后果

P2 解除 P1 对**显式 DeepSeek SAFE_LOCAL** Tool 请求的抑制，但不实现 URL/provider 猜测、任意 Provider fields、
thinking 输出、Dashboard/持久诊断或远程 Provider Smoke。DashScope 仍保持 P1 的 Tool Schema 抑制，直到有独立的
续轮证据与 Contract。
