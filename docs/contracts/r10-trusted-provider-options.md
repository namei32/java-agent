# R10-P1 受信 Provider Options Contract

- 阶段：R10-P1
- 状态：已实现并验证
- Fixture：`testdata/golden/provider/r10-provider-options-v1.json`
- ADR：[ADR-0032](../adr/0032-use-explicit-text-only-trusted-provider-options.md)
- Python 证据：`agent/provider.py::DeepSeekStrategy`、`DashScopeStrategy`（基线 `b65a543`）

## 1. 激活与值域

配置根为 `agent.model.provider-options`，默认值必须为：

```yaml
profile: DISABLED
thinking-mode: DISABLED
reasoning-effort: NONE
```

三个值均严格使用大写枚举，禁止空值、前后空白和未知值。`DISABLED` Profile 不能组合 thinking 或 effort。`DASHSCOPE`
仅接受 `thinking-mode=ENABLED` 和 `reasoning-effort=NONE`；`DEEPSEEK` 至少需要 ENABLED thinking 或 `LOW`/`MEDIUM`/`HIGH`
effort。配置必须在 Adapter Bean 创建时失败，不得降级为猜测或静默转发。

P1 只接收这些 Java-native Properties；Python TOML 的 thinking/effort/`extra_body` 保持 Deferred。它不根据模型名、
base URL 或 provider 名猜测 Profile。

## 2. 请求映射

当且仅当 Profile 非 `DISABLED` 且 `ChatModelRequest.tools()` 为空时：

| Profile | thinking mode | reasoning effort | 唯一可发送的 Provider 字段 |
| --- | --- | --- | --- |
| `DEEPSEEK` | `ENABLED` | 任意合法值 | `thinking={"type":"enabled"}`，可另带顶层 `reasoning_effort=low|medium|high` |
| `DEEPSEEK` | `DISABLED` | `LOW`/`MEDIUM`/`HIGH` | 仅顶层 `reasoning_effort=low|medium|high` |
| `DASHSCOPE` | `ENABLED` | `NONE` | `enable_thinking=true` |

任何 Tool Schema（即使该轮最终不执行 Tool）都必须清除 reasoning effort 和 Provider extra body。唯一例外是已由
[R10-P2](r10-reasoning-tool-continuation.md) 显式启用的 `DEEPSEEK` + `SAFE_LOCAL`：为使其同轮 Tool continuation
可用，可保留本表的固定 DeepSeek thinking/effort。`DASHSCOPE` 始终清除。模型、温度、已有 Headers、stream transport
header 与 SchemaOnly Tool Callback 必须保持；非 `OpenAiChatOptions` 不获得通用 Options 替换或注入。同步和流式文本请求
使用同一映射。

## 3. 禁止项与 P2 边界

禁止传递任意 `extra_body`/metadata/header/cache key/用户文本/未知键；不得读取或输出 `reasoning_content`、`<think>`、
native Usage 或 Provider fields。P1 不写 SQLite、日志、Prompt、Channel、HTTP/CLI，也不重试或改变取消。P2 仅以其
Contract 所列的单轮、内存 `reasoningContent` metadata 例外解除 DeepSeek 的 Tool Schema 抑制；它不是可持久化或通用
Provider fields 开关。

## 4. 验收

Fixture 固定默认零注入、DeepSeek effort、DeepSeek/DashScope 固定 thinking body、Tool Schema 抑制和非法组合拒绝。
生产 `TrustedProviderOptions` 必须消费每一个 Case；Adapter 测试还验证 Options runtime type、模型/温度/Header/Tool
Callback 保留。项目内 `127.0.0.1` Stub 验证同步/流式 JSON 只有 allowlisted 字段，且 Tool 请求不带这些字段。默认、
`failure`、`compat` 三套完整门禁均通过；不调用真实 Provider。
