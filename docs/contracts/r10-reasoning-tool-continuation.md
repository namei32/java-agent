# R10-P2 有界 reasoning / Tool continuation Contract

- 阶段：R10-P2
- 状态：已实现并验证；默认 `DISABLED`
- Fixture：`testdata/golden/provider/r10-reasoning-tool-continuation-v1.json`
- ADR：[ADR-0033](../adr/0033-replay-bounded-provider-reasoning-only-within-one-tool-loop.md)
- Python 证据：`agent/provider.py::DeepSeekStrategy.extract_message`、`provider_fields_for_tool_call`、`agent/core/passive_turn.py` 的 Tool 消息组装（基线 `b65a543`）

## 1. 激活与范围

`agent.model.reasoning-continuation.mode` 是严格大写枚举：

| 值 | 行为 |
| --- | --- |
| `DISABLED` | 默认；P1 保持任何 Tool Schema 零 thinking/effort 注入，所有 reasoning 丢弃。 |
| `SAFE_LOCAL` | 仅 `DEEPSEEK` Profile 且同一响应含 Tool Call 时允许有界内存续轮。`DASHSCOPE` + `SAFE_LOCAL` 在 Bean 创建时拒绝。 |

`SAFE_LOCAL` 不根据 URL、模型名、Python TOML 或 response body 推断 provider；`DASHSCOPE` 和非 `OpenAiChatOptions`
仍保持 Tool Schema 抑制。P1 的文本路径不变。

## 2. 数据路径与上限

Adapter 仅从 Spring AI `AssistantMessage` metadata 的 `reasoningContent` 读取 String；若不存在，才对 assistant content
中的完整 `<think>...</think>` 段提取并从 content 删除该段，匹配 Python 的通用策略。提取结果必须 strip、非空且不超过
16,384 Unicode code points。超长、畸形、空白或非 String 值一律丢弃，不能以截断内容、异常、日志或用户文本替代。

`ChatModelResponse` 可在内存中携带该受限值，但 `ToolLoop` 只在该响应有 Tool Call 时构造临时
`AssistantToolCallMessage`。Adapter 将它映射到 Spring Assistant metadata 的 `reasoningContent`，因此 Spring AI 只在下一
assistant Tool Call wire message 中写 `reasoning_content`。最终回答、Tool Result、历史和 `PersistedTurn` 都不含该值。

## 3. 安全与失败不变量

reasoning 不得写入 SQLite、Session、Prompt、生命周期事件、Provider Usage Observer、日志、HTTP/CLI/Channel Message、
MCP 或错误对象。取消发生在模型返回后、Tool 前、Tool 后或续轮前时，必须阻止下一请求且无持久化。Tool/Provider 失败、
P3 上下文恢复候选失败、超限、最终无 Tool 文本或 append 失败均没有可观察 reasoning 残留。

`SAFE_LOCAL` 只改变符合条件的 DeepSeek Tool 请求：允许 P1 固定 thinking/effort Options 随 Tool Schema 发送；它不
改变模型、温度、Headers、Callback、Tool 执行、批准、Ledger、取消或 P3 的“Tool 后零恢复”规则。

## 4. 验收

Fixture 的 6 个 Case 固定默认抑制、DeepSeek SAFE_LOCAL 允许、DashScope 启动期拒绝、最终文本不回放、缺失/超长
reasoning 丢弃和 16,384 code-point 边界。生产 Policy 消费全部 Case。Adapter/ToolLoop 测试覆盖同步、分段 SSE、
`reasoning_content`、`<think>`、连续 Tool 调用只回放到对应下一请求、取消后零续轮/零持久化及模型/Tool Options 保留。
全部测试只用 Fake 或 `127.0.0.1` Stub；不调用真实 Provider。完整默认、`failure`、`compat` 门禁作为本切片最终证据。

## 5. 已知的 Python 历史语义差异（P2b，未实现）

Akashic 的 `DeepSeekStrategy` 会在 thinking 请求中为缺失的历史 assistant `reasoning_content` 回填空字符串，并将非空
reasoning 写入 Session history；其 `Session.get_history` 也会继续携带该字段。当前 Java P2 有意不实现这条路径：
`ProviderReasoning` 只能跨越当前 Tool Call 到紧随的一次请求，随后即丢弃；Spring AI 2.0 只有 metadata 为非空文本时
才会写 wire `reasoning_content`。

因此 P2 是受限的即时 Tool continuation 对齐，不是 DeepSeek 的跨 Turn reasoning 历史兼容。若需要 P2b，必须先单独
批准 reasoning 的会话保留、数据保留期、日志/导出/删除边界和 Spring AI 空字段的受控请求扩展；不能把它作为 P2 的
隐式补丁或借由任意 Provider body 注入。
