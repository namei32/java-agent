# R10 Provider 协议、失败语义与思考数据对齐计划

- 状态：P0 已完成并离线验证；P1–P4 未开始
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
| 提取 `reasoning_content` / `<think>`、在 Tool Call 后回传 Provider fields | Kernel `ChatModelResponse` 只包含正文与 Tool Call；Java 不保存或回传 reasoning | P2 必须先获得对 reasoning 数据保留、可见性、审计和渠道输出的明确决策 |
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

### P1：受信 Provider Options 映射（RED → GREEN，待设计复核）

只在 P0 全绿后，才决定是否激活一个小型、严格 allowlisted 的 Java Properties 映射。每个 provider strategy 必须有
本地请求 JSON Fixture、未知键拒绝、默认零注入、Tool continuation、模型/Options 类型保留和取消测试。任意原样
转发 `extra_body`、从 Python TOML 激活私有参数或真实 Provider Smoke 都不在本切片。

### P2：Thinking 与 Tool continuation 数据（需显式数据保留决策）

先决定 reasoning 是否根本允许离开 Provider Boundary；若允许，仍需规定最大长度、只读内存性、禁止 Channel/SQLite/日志、
Tool continuation 的准确字段和清理期限。没有这份决策时，Java 继续丢弃 reasoning，并不把 Python 的 `provider_fields`
当作可持久化元数据。

### P3：上下文超限恢复语义（需独立 Prompt/重试 Contract）

Python 在 `ContentSafetyError`/`ContextLengthError` 后切换 history/context 裁剪计划。Java 只能在固定、明确的尝试次数、
无 Tool 执行/无副作用重放、Cancellation、预算和 Session Commit 隔离均可证明时引入这一恢复；不可把 P0 的分类自动
变成请求重试。

### P4：缓存 Usage 与受限观测（需独立观测 Contract）

定义只包含聚合整数、无模型 payload/Cache Key/Session 原文的 Observation Port；再决定是否能进入本地 Operator View。
它依赖 R13 的只读控制面边界，不能以 Python Dashboard 为由扩展 Web API 或前端。

## 4. 验收与文档规则

- P0 已完成 RED Fixture/测试与 Green 实现，并通过模块定向测试以及默认、`failure`、`compat` 三套完整门禁。P0 是
  错误协议扩展，不构成对 P1–P4 的完成声明。
- 只有已由生产代码消费、Manifest SHA 校验通过的 Fixture 才是验收证据；单纯解析 JSON 不足以声明完成。
- 每个切片同一提交更新本计划、能力矩阵、完成度审计、Golden 清单和 HTTP/Channel Contract（如果稳定码增加）。
- P1–P4 均保持未开始，直到各自的 Contract/数据决策和必要授权完成。真实 Provider Smoke 仍为独立运行授权。
