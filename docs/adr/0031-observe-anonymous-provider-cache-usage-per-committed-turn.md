# ADR-0031：只观察已提交 Turn 的匿名 Provider 缓存用量

- 状态：已接受
- 日期：2026-07-19
- 阶段：R10-P4
- 关联 Contract：[R10-P4 Provider 缓存用量观察](../contracts/r10-provider-cache-usage-observation.md)

## 背景

Akashic 会从 Provider Usage 中提取 `cache_prompt_tokens` 和 `cache_hit_tokens`，并按被动 Turn 聚合，供其本地
dashboard/诊断读取。Java 的 Spring AI Adapter 此前直接丢弃所有 `ChatResponseMetadata.usage`，因此既无法得到这项
运营信号，也不应为了补齐它而复制 Python 的 Dashboard、Session 原文或 Provider 原始 Usage。

## 决策

Java 只在 Spring AI Adapter 边界读取标准 `Usage.getPromptTokens()` 与 `Usage.getCacheReadInputTokens()`；绝不读取
`getNativeUsage()`、metadata id、model、rate limit、请求/响应或缓存键。只有两项均为非负整数且
`cacheReadInputTokens <= promptTokens` 时，才形成一个 `ProviderCacheUsage` 样本。

Application 在单个逻辑 Turn 内对已成功收到的模型响应计数，并只对可用样本累加这两个数。Session 成功 append 后，
才将 `ProviderTurnUsage` 交给一个内部、best-effort 的 `ProviderUsageObserver`；它没有 session id、turn id、模型名、
时间、消息、Tool 参数、cache key、Provider body 或错误字段。观察器默认 no-op，观察器异常不得影响已提交的 Turn。

流式响应只使用完成前最后一个可用的标准 usage 样本；中间 Delta、thought/reasoning、native Usage 与无效值一律不
保留。失败、取消、超限恢复候选失败、Tool/Channel/Session 提交失败均不发布观察事件。

## 后果

这实现了 Python cache usage 的安全、Java-owned 最小等价物，但不实现 Python Dashboard、SQLite 持久化、日志正文、
HTTP API、前端或控制面查询。是否以不改变控制面边界的方式进入本地 Operator View，留待 R13 的只读控制面 Contract
明确后再决定。
