# R10-P0 Provider 失败分类 Contract

- 状态：已实现并通过离线阶段门禁
- Fixture：`testdata/golden/provider/r10-provider-failure-v1.json`
- ADR：[ADR-0028](../adr/0028-classify-provider-rejections-at-adapter-boundary.md)
- 关联计划：[R10 Provider 协议对齐](../plans/2026-07-19-r10-provider-protocol-alignment-plan.md)

## 范围

P0 仅将 OpenAI-compatible Provider 的两类已知失败投影为 Java 稳定失败码：

| Provider 语义 | Kernel 异常 | Channel Code | retryable |
| --- | --- | --- | --- |
| 内容安全拒绝 | `ModelSafetyRejectedException` | `MODEL_SAFETY_REJECTED` | `false` |
| 上下文超限 | `ModelContextLimitException` | `MODEL_CONTEXT_LIMIT` | `false` |

同步 `call` 和 SSE `stream` 必须使用相同分类。通用上游失败仍为 `ModelInvocationException` /
`MODEL_UNAVAILABLE`；传输 Timeout 仍优先为 `ModelTimeoutException` / `MODEL_TIMEOUT`。分类不得触发重试。

## 受限匹配与脱敏

仅检查异常 Cause 链中的受限、大小写无关标记；命中后异常 message 必须是固定中文文案，且不得含 Provider body、
Prompt、Authorization、模型名、会话、Tool 参数或原始标记附近的文本。

安全标记：`data_inspection_failed`、`content_filter`、`content_policy_violation`。

上下文标记：`range of input length`、`context_length_exceeded`、`maximum context length`、
`context window exceeds limit`、`string too long`、`reduce the length`、`too many tokens`。

Cause 链必须有有限上限和循环保护。若没有命中，或底层错误没有可读 message，必须安全地归入既有通用错误，不能猜测。

## 版本化协议影响

`TURN_FAILED` 继续要求空 content、正 sequence，并由 `TurnFailureCode` 唯一决定 `retryable`。P0 新增的两个码均不可
重试，因而调用方不得自行把它们改成 `true`。HTTP 层可以继续用通用且无敏感数据的 `502` 投影；P0 不承诺 Python 的
正常文本 fallback 或上下文裁剪重试。

## 明确排除

P0 不激活 `llm.main.thinking`、`enable_thinking`、`reasoning_effort` 或 `extra_body`，不保存/输出 reasoning、
cache usage 或 provider fields，不增加 Provider、网络、配置、数据库和真实 Smoke。它不实现 Python 的自动裁剪恢复。
