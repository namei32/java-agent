# R10 Provider 协议、失败语义与思考数据对齐计划

- 状态：P0–P4 已完成并离线验证
- 日期：2026-07-19
- Python 证据基线：`akashic-agent` 提交 `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Java 证据基线：`agent/r12-skill-catalog`，含 Spring AI OpenAI-compatible 同步/流式 Adapter
- 前置：[Provider Streaming 与本地 CLI 契约](../contracts/provider-streaming-cli.md)、[完成度审计](../architecture/2026-07-19-akashic-java-completion-audit.md)

## 1. 审计结论

Java 已可靠覆盖 OpenAI-compatible 文本、Tool Schema/Call/Result、SSE 文本流、空闲超时、定向传输取消和有界 Tool
arguments。适配器从 `chatModel.getOptions()` 取得实际 Provider Options，并保留 `OpenAiChatOptions` 的模型和已有
配置后只追加 Tool Callback；本地 HTTP Stub 已验证请求和续轮消息。

但 Python `agent/provider.py` 还有四类未迁移行为：

| Python 行为 | Java 当前事实 | 对齐判定 |
| --- | --- | --- |
| 识别内容安全拒绝与上下文超限，并让被动轮次切换裁剪计划 | 除 Timeout 外的上游异常统一投影为 `ModelInvocationException` / `MODEL_UNAVAILABLE` | P0 先增加安全的稳定失败分类；Python 的多轮裁剪恢复另列 P3 |
| DeepSeek/DashScope 按 provider/base URL/model 规范化消息与 thinking 参数 | 配置把 `llm.main.thinking`、`enable_thinking`、`reasoning_effort`、`extra_body` 记为 Deferred；不会送入 Spring AI | P1 需定义受信、版本化 Options 映射，不能把任意 TOML 转发给 Provider |
| 提取 `reasoning_content` / `<think>`、在 Tool Call 后回传 Provider fields | `ProviderReasoning` 仅能附着当前 `ChatModelResponse` 与临时 `AssistantToolCallMessage`；Spring AI metadata 只回传下一条 assistant Tool Call | P2 已以 ADR-0033 固定单轮、16,384 code-point、内存-only 边界；不持久化、不进入日志/Channel/HTTP/CLI |
| 统计 prompt/cache token、暴露给 Python dashboard/诊断 | Java 无相应 Port 或受限观测投影 | P4 要先建立匿名聚合指标与防泄漏 Contract；不复用 Dashboard 或日志正文 |

因此不得把“已有 OpenAI-compatible Adapter”称为 Python Provider 策略全量对齐，也不得为追求兼容而把原始
reasoning、原始 Provider 错误、缓存键或任意 `extra_body` 写入 SQLite、日志、Channel 或 Prompt。

## 2. 不可跨越的边界

1. 所有 P0 自动化都只调用项目内的 `127.0.0.1` HTTP Stub；不访问真实 Provider、Secret、用户消息或付费模型。
2. P0 只根据已捕获的异常类型/受限错误标记产生固定错误码和通用文案。不得把 Provider Error Body、Prompt、请求头或
   原始消息写入异常、Channel、HTTP 响应或日志。
3. Python 的内容安全/上下文超限最终向用户返回一段正常文本，而 Java 当前版本化 Channel Contract 的失败终态只允许
   空内容和稳定 Code。P0 先保留 Java 的终态协议，并记录这是一项**有意、尚未完成的用户体验差异**；不能伪造逐字对齐。
4. thinking、`reasoning_content`、Tool continuation field、cache token 和供应商特定参数均不因 P0 获得数据持久化、
   Channel 输出、配置激活或网络运行授权。
5. 已有 `agent.model.stream-idle-timeout`、Transport Cancellation、Tool 消息回送和 Options runtime-type 保留语义保持
   不变；P0 不重试模型请求，避免隐式重复 Tool/Provider 调用。

## 3. 连续 TDD 切片

### P0：Provider 失败分类（RED → GREEN，已完成）

冻结 Java-owned `provider/r10-provider-failure-v1` Fixture，覆盖同步与流式的：内容安全标记、上下文超限标记、
Timeout 优先级、未知上游失败、嵌套 Cause、原始错误脱敏、稳定 retryable 值和不重试。使用本地 Stub/Fake ChatModel
令 `SpringAiChatModelAdapter` 在边界处映射为专用 Kernel 异常，`TurnFailureClassifier` 再映射为稳定版本化 Channel
Failure Code。9 个生产消费的 Fixture Case 覆盖同步与流式、嵌套 Cause、Timeout 优先级、未知错误和脱敏；同步 HTTP
错误投影继续无敏感数据。

P0 不能实现 Python 的“裁剪后自动重试”，不能新建 Provider Profile、不能设置 thinking、不能存储 Usage，且不改变
默认 Provider 配置。

### P1：受信 Provider Options 映射（已实现并验证）

已选择并实现方案 B，详见[受信 Provider Options 决策记录](../specs/2026-07-19-r10-provider-options-decision.md)和
[Contract](../contracts/r10-trusted-provider-options.md)。严格 Java-native `agent.model.provider-options.*` 默认
`DISABLED`；`DEEPSEEK` 只允许固定 thinking body 和/或 `low|medium|high` effort，`DASHSCOPE` 只允许固定
`enable_thinking=true`。未知/空白/无效组合在 Bean 创建时拒绝，Python TOML 的 thinking/effort/`extra_body` 仍是
Deferred。只有无 Tool Schema 的同步/流式文本请求会注入这些字段；任一 Tool Schema 都清除 Provider options，同时保留
模型、温度、Headers 与 Callback。7 场景 Fixture 已由生产 Policy 消费，本地 Stub 已验证 JSON 和默认零注入；默认、
`failure`、`compat` 三套完整门禁均通过。它仍不保留 reasoning，也不实现 Python 的 Tool-thinking 续轮。

### P2：Thinking 与 Tool continuation 数据（已实现并验证）

[ADR-0033](../adr/0033-replay-bounded-provider-reasoning-only-within-one-tool-loop.md)、
[Contract](../contracts/r10-reasoning-tool-continuation.md) 和 6 Case Java-owned Fixture 已固定并由生产
`TrustedProviderOptions` 消费：`DISABLED` 保持零回放；只有显式 `DEEPSEEK` + `SAFE_LOCAL` 允许 Tool Schema 携带固定
thinking/effort，并让 Spring AI 的 `reasoningContent` metadata 或完整 `<think>...</think>` 片段以最多 16,384 Unicode
code points 跨越一次内存 Tool continuation。`DASHSCOPE` + `SAFE_LOCAL` 启动即拒绝。同步本地 HTTP Stub 验证
`reasoning_content` 只在第二条 assistant Tool Call wire message 出现；分段 SSE 测试验证 thought 从不发布给 observer。
`ToolLoop` 测试验证取消后没有第二次模型调用或持久化。P2 不保存 reasoning、不改变 Tool 执行/审批/P3 恢复、不支持 URL
推断、任意 Provider fields 或真实 Provider Smoke。

### P3：上下文超限恢复语义（已实现并验证）

Python 在 `ContentSafetyError`/`ContextLengthError` 后切换 history/context 裁剪计划。P3 的
[安全恢复 Contract](../contracts/r10-context-limit-recovery.md)与 ADR-0030 已固定 Java 的更窄边界：默认关闭，仅非流式、
任何 Tool 执行前的 `ModelContextLimitException` 可在确定 Prompt/History 候选间重试；不重放 Tool/副作用，不改写历史，
耗尽时仍按 P0 失败。实现提供严格 `agent.context-limit-recovery.mode`（`DISABLED`/`SAFE_LOCAL`）与默认关闭的
Bootstrap 接线；Prompt 最低裁剪计划、尾部历史缩减、流式/取消/Tool 后零重试和单次提交均有离线测试。默认、`failure`、
`compat` 三套完整 Reactor 门禁已通过。它不可把 P0 的分类自动变成调用方可重试的协议。

### P4：缓存 Usage 与受限观测（已实现并验证）

P4 的[匿名缓存用量观察 Contract](../contracts/r10-provider-cache-usage-observation.md)与 ADR-0031 固定了一个比 Python
Dashboard 更窄的 Java-owned 迁移：Adapter 只读取标准 prompt/cache-read 整数，`ToolLoop` 只聚合有效 pair，且仅在
Session append 成功后向默认 no-op 的内部 Port 交付三项聚合数。5 场景 Fixture 已由生产聚合器消费；同步/流式投影、
最后可用 stream usage、无效值丢弃、Tool Loop 累加、失败/取消/append 失败零发布及 Observer 隔离均有离线测试。
默认、`failure`、`compat` 三套完整门禁均已通过。它不记录模型 payload、Cache Key、Session 原文或 Provider native
Usage，也不能以 Python Dashboard 为由扩展 Web API、前端、SQLite 或日志。

## 4. 验收与文档规则

- P0 已完成 RED Fixture/测试与 Green 实现，并通过模块定向测试以及默认、`failure`、`compat` 三套完整门禁。P0 是
  错误协议扩展，不构成对 P1–P4 的完成声明。
- 只有已由生产代码消费、Manifest SHA 校验通过的 Fixture 才是验收证据；单纯解析 JSON 不足以声明完成。
- 每个切片同一提交更新本计划、能力矩阵、完成度审计、Golden 清单和 HTTP/Channel Contract（如果稳定码增加）。
- P0–P4 均已完成离线实现与门禁；P1/P2/P4 的受限 Options、单轮 reasoning 和匿名观测均不构成 Python Dashboard/诊断 UI、
  任意 Provider fields 或真实 Provider Smoke 授权。真实 Provider Smoke 仍为独立运行授权。
