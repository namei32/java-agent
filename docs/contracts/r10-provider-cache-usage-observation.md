# R10-P4 Provider 缓存用量与受限观察 Contract

- 阶段：R10-P4
- 状态：Contract/Fixture 已冻结，TDD 实现中
- Fixture：`testdata/golden/provider/r10-provider-cache-usage-v1.json`
- ADR：[ADR-0031](../adr/0031-observe-anonymous-provider-cache-usage-per-committed-turn.md)
- Python 证据：`agent/provider.py::_extract_cache_usage`、`agent/core/passive_turn.py` 的 `react_cache_*` 聚合（基线 `b65a543`）

## 1. 投影边界

Spring AI Adapter 只可从标准 `ChatResponseMetadata.getUsage()` 读取：

- `getPromptTokens()`；
- `getCacheReadInputTokens()`。

它不得读取 `getNativeUsage()`、metadata id/model、rate limit、请求、响应、缓存键、Provider 错误或 reasoning。任一值
缺失、为负数或 cache read 大于 prompt 时，该响应没有可用 cache usage；这不是模型调用失败，也不会向用户暴露原因。

一个可用样本为 `(promptTokens, cacheHitTokens)`，两个值均为非负整数。`cacheHitTokens = 0` 是**可观察**的 cache
样本，不能与缺失混淆。

## 2. Turn 聚合与发布

`modelCallCount` 计入每个已得到有效 `ChatModelResponse` 的模型调用，包括没有 usage 的响应和携带 Tool Call 的响应。
只有至少一个可用样本时，`cachePromptTokens` 与 `cacheHitTokens` 才存在，且分别是全部可用样本之和；无可用样本时两者
必须同时为 `null`。不可用样本不得被当作零，也不得阻止其他可用样本进入和。

只在当前 User/Assistant Turn 已成功 append 后，才向内部 `ProviderUsageObserver` 发布一次聚合。失败、取消、任何
恢复候选失败、stream 未完成、Tool 执行后失败或 append 失败时均发布零次。Observer 为 best-effort：其异常必须被隔离，
不得撤销提交、改变聊天结果、写入日志或触发重试。

流式路径取完成前最后一个可用 usage 样本，并在流完成形成同一个 `ChatModelResponse` 后按上述规则计数。它不从
delta 文本、thought/reasoning 或 native payload 推导 usage。

## 3. 隐私、安全和运行边界

Observer 输入只能是 `modelCallCount`、`cachePromptTokens`、`cacheHitTokens` 三个聚合数。默认 Observer 为 no-op；
R10-P4 不增加 SQLite 表/列、日志字段、HTTP/Channel/Telegram Message、MCP 资源、CLI、Web API 或前端。真实
Provider、网络、付费调用与 Dashboard 仍冻结。

## 4. 验收

Java-owned Fixture 固定空 Turn、无 cache、零命中、跨 Tool Loop 累加以及“只有有效 pair 参与”的语义。生产聚合代码
必须消费全部 Case。Adapter 测试另覆盖同步、流式、最后可用 stream usage、无效/缺失值丢弃以及绝不读取 native Usage；
Application 测试覆盖成功后单次发布、Tool Loop、失败/取消/append 失败零发布和 Observer 隔离。

所有测试使用 Fake ChatModel、内存 Session 或临时 SQLite；不访问真实 Provider、网络、Telegram、MCP、Workspace 或 Side
Effect Capability。完成实现后执行默认、`failure`、`compat` 三套完整门禁。
