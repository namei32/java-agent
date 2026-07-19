# R10-P1 受信 Provider Options 决策记录

- 状态：已审计；等待运行语义选择后实施
- 阶段：R10-P1
- 前置：[R10 Provider 协议对齐计划](../plans/2026-07-19-r10-provider-protocol-alignment-plan.md)、[R10-P0 失败分类 Contract](../contracts/r10-provider-failure-classification.md)

## 1. 已验证事实

Python `agent/provider.py` 依据 provider 名称、base URL 和模型名选择 DeepSeek 或 DashScope 策略。它会把
`enable_thinking`、`reasoning_effort` 和任意 `extra_body` 写入请求；DeepSeek 还会在 Tool Call 后回传
`reasoning_content`。`bootstrap/providers.py` 会为 light provider 强制关闭 thinking。

Java `OpenAiChatOptions` 已提供 `reasoningEffort` 和 `extraBody` 字段，但 Java 的 Python TOML 兼容层故意把
`llm.main.thinking`、`enable_thinking`、`reasoning_effort` 和根级 `extra_body` 标为 `DEFERRED`。这不是遗漏：当前
`ChatModelResponse` 没有 reasoning/provider-field 容器，Tool continuation 也尚未定义。

## 2. P1 不变量

1. 默认必须为 `DISABLED`，没有显式 Java-native Properties 时不得改变任何 Provider 请求。
2. 不根据 base URL、模型名称或 Python TOML 推测 Provider Profile；Profile 必须是有限枚举。
3. 禁止透传 `extra_body`、`metadata`、header、cache key、用户文本或未知键。所有允许字段均为固定名称、固定类型、固定枚举。
4. P1 不读取、写入或输出 reasoning/provider fields，不写 SQLite、日志、Channel、HTTP 响应或 Prompt。
5. 任何会发送 thinking 的 Profile 都必须规定 Tool Schema 存在时的行为；在 P2 固定 continuation 前，不能假设 Provider 接受缺失的 reasoning 字段。
6. 全部验证使用 Fake ChatModel 或项目内 `127.0.0.1` Stub；不运行真实 Provider Smoke。

## 3. 必须选择的运行语义

| 方案 | 允许的请求行为 | Tool 轮次 | P2 影响 | 结论 |
| --- | --- | --- | --- | --- |
| A：继续冻结 | 不发送任何 thinking/effort 字段 | 不变 | P2 先定义数据与 continuation | 最保守；不缩小 P1 差距 |
| B：文本轮次受信请求 | 显式 Profile 可发送 allowlisted thinking/effort；reasoning 在 Adapter 边界丢弃 | 只要请求携带任一 Tool Schema，就强制不发送 thinking 字段 | P2 后再考虑 Tool continuation 与可见性 | 推荐的最小 P1；默认仍关闭 |
| C：完整 thinking/Tool 策略 | 请求 thinking 并在 Tool 续轮保留 Provider 字段 | 必须回传受限 `reasoning_content` | 先完成 P2 Contract、数据保留与审计决策 | 不能在 P1 单独实施 |

方案 B 不会宣称与 Python 的 Tool-thinking 链路等价：它仅在没有 Tool Schema 的文本请求中使用固定 allowlist，响应中的
reasoning 永不离开 Provider Adapter。若用户选择 A，P1 保持设计完成但不增加运行行为；若选择 C，必须先转入 P2。

## 4. 方案 B 的实现切片（仅在明确选择后）

1. 冻结 `provider/r10-provider-options-v1` Java-owned Fixture：默认零注入、显式 Profile 的请求 JSON、未知 Profile/
   非法 effort 拒绝、已有模型/温度/Header/Tool Callback 保留、Tool Schema 强制抑制、同步/流式一致和取消不变。
2. 新增严格 Java-native `agent.model.provider-options.*` Properties 与不可变 Policy；Python TOML 字段继续 `DEFERRED`。
3. 仅对运行时 `OpenAiChatOptions` 使用 `mutate()`；不替换 Options 类型，不创建通用 `ChatOptions`，不影响非 OpenAI
   模型的既有请求。
4. 先 RED 本地 Stub JSON 断言，再 GREEN；完成后跑默认、`failure`、`compat` 三套门禁并同步能力矩阵。

允许的 profile、字段和值必须与 Fixture 一一对应，候选范围限于：`DEEPSEEK` 的 `reasoning_effort=low|medium|high`，以及
`DASHSCOPE` 或 `DEEPSEEK` 的布尔 thinking 开关。具体 JSON 外形在选定方案后由 Fixture 固定，不能从用户配置对象直接复制。
