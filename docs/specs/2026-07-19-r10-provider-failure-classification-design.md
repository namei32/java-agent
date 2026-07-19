# R10-P0 Provider 失败分类设计

## 设计目标

让 Provider Boundary 在不泄露原始上游错误、不改变请求次数的前提下，区分 Python 已显式处理的内容安全与上下文超限。
分类发生在 `adapter-spring-ai`；业务和渠道只接收 Kernel 稳定异常/码。

## 流程

```text
local ChatModel / OpenAI-compatible HTTP Stub failure
        │
        ▼
SpringAiChatModelAdapter (有限 Cause 扫描、Timeout 优先)
        │
        ├─ safety marker  ──► ModelSafetyRejectedException
        ├─ context marker ──► ModelContextLimitException
        ├─ timeout         ──► ModelTimeoutException
        └─ other           ──► ModelInvocationException
        │
        ▼
TurnFailureClassifier → TURN_FAILED stable code / fixed retryable
```

`generate` 与 `mapStreamingFailure` 必须共用同一分类规则，避免 SSE 与同步入口产生不同的终态。固定异常消息由
Kernel 类提供；Cause 仅用于本地诊断链，任何外部投影均使用稳定码。

## 有意差异

Python `passive_turn.py` 会在安全或上下文异常后尝试不同的 Prompt 裁剪计划，并在最终失败时产生普通回复文本。
Java 的版本化 Channel Contract 已固定失败终态为空内容，且当前模型调用后可能进入 Tool/Session 提交边界。因此 P0
只建立分类，绝不自动重放、裁剪或提交 fallback。这一差异由后续 P3 的独立重试/提交隔离 Contract 解决。

## 验证

版本化 Fixture 覆盖同步/流式、嵌套 Cause、Timeout 优先、未知失败、固定异常 message 和 Channel `retryable`。本地
OpenAI-compatible HTTP Stub 额外验证真实 Spring AI 上游异常能通过同一边界分类。所有测试使用虚构 marker 和
`127.0.0.1`，不含凭据或用户数据。
