# ADR-0032：使用显式、仅文本的受信 Provider Options

- 状态：已接受
- 日期：2026-07-19
- 阶段：R10-P1
- 关联 Contract：[R10-P1 受信 Provider Options](../contracts/r10-trusted-provider-options.md)

## 背景

Akashic 按 provider/base URL/model 推断 DeepSeek、DashScope 等策略，并转发任意 `extra_body`。当 DeepSeek thinking
产生 Tool Call 时，它还依赖 `reasoning_content` 续轮。Java 既不能通过 URL 推测供应商，也尚未拥有 P2 所需的
reasoning/continuation 数据保留 Contract。

## 决策

采用方案 B：`agent.model.provider-options.*` 默认严格 `DISABLED`；仅明确的 `DEEPSEEK` 或 `DASHSCOPE` Profile 可在
**没有任何 Tool Schema** 的文本同步/流式请求中发送固定 allowlist。

- `DEEPSEEK` 只允许 `reasoning_effort=low|medium|high` 和/或固定
  `thinking={"type":"enabled"}`；
- `DASHSCOPE` 只允许固定 `enable_thinking=true`，不接受 reasoning effort；
- Profile、thinking mode 和 effort 都是严格大写枚举，未知、空白或无效组合在启动时拒绝；
- 存在任意 Tool Schema 时清除 reasoning effort 和 extra body，仍保留 OpenAI Options 的模型、温度、Headers 与
  Tool Callback；
- Python TOML 的 `llm.main.thinking`、`enable_thinking`、`reasoning_effort` 和根级 `extra_body` 继续 `DEFERRED`。

响应中的 reasoning 不离开 Adapter；P1 不新增 Kernel 字段、SQLite、日志、Channel、HTTP/CLI 输出或真实 Provider 调用。

## 后果

Java 覆盖了不含 Tool 的受信 thinking/effort 请求子集，但没有实现 Python 的 provider 推断、任意 body 转发或
thinking-Tool continuation。后者只能在 P2 独立冻结最大长度、内存期限、禁止外泄与续轮精确字段后实施。
