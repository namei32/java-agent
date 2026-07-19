# ADR-0028：在 Provider Adapter 边界分类安全与上下文拒绝

- 状态：已接受
- 日期：2026-07-19
- 决策者：Namei Agent Java 迁移维护者
- 关联：[R10-P0 Provider 失败分类 Contract](../contracts/r10-provider-failure-classification.md)

## 背景

Python `agent/provider.py` 区分内容安全拒绝和上下文超限；Java 的 Spring AI Adapter 此前除 Timeout 外将所有上游
运行时错误统一映射为 `ModelInvocationException`。因此版本化 Channel 无法安全区分不可重试的策略/请求限制与可重试的
上游不可用，也不能证明同步和 SSE 一致。

## 决策

1. 在 `adapter-spring-ai` 的 Provider Boundary 扫描最多 32 个 Cause，并使用 identity 循环保护。
2. 固定的、大小写无关 marker 映射到 `ModelSafetyRejectedException` 或 `ModelContextLimitException`；两者在
   `TurnFailureCode` 中分别是不可重试的 `MODEL_SAFETY_REJECTED` 和 `MODEL_CONTEXT_LIMIT`。
3. 传输 Timeout 优先于文本 marker。未知错误仍是既有的可重试 `MODEL_UNAVAILABLE`。
4. 分类异常只使用固定公开 message；原始 Provider payload 只作为内部 Cause，不进入 HTTP、Channel、日志或 Fixture
   期望值。
5. P0 不重试、不裁剪 Prompt、不写入 reasoning/cache/provider fields，也不激活任何 Provider-specific Options。

## 后果

- 同步与流式调用获得同一稳定失败语义，且 Channel 的 `retryable` 继续只由枚举确定。
- Java 仍不同于 Python 在失败后尝试 Prompt 裁剪和产出正常 fallback 文本；该行为须在独立、无 Tool/副作用重放的
  P3 Contract 中证明。
- 新 marker 必须经过 Fixture、脱敏测试和本地 HTTP Stub 验证；不能为真实 Provider 的偶然错误文案无限扩大匹配集合。
