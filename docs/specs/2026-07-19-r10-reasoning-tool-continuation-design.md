# R10-P2 有界 reasoning / Tool continuation 设计

- 状态：已实现并验证；默认关闭

```text
OpenAI-compatible response
  reasoning_content / <think> segment
              │
              ▼
bounded ProviderReasoning? (memory only)
              │   only response has Tool Call
              ▼
AssistantToolCallMessage (current ToolLoop list)
              │
              ▼
Spring Assistant metadata[reasoningContent]
              │
              ▼
next provider request: assistant reasoning_content + Tool Call + Tool Result
              │
              ▼
final ChatModelResponse → PersistedTurn(content only)
```

这个值只跨越一个模型响应到紧随的续轮请求；它不是 Session history，也不进入 `PersistedTurn`。多 Tool response 仍使用
同一 assistant message 的单一 reasoning；ToolLoop 的下一模型调用完成后，该临时 message list 不再被外部持有。
`SAFE_LOCAL` 对 Tool request 的 Options 放行仅限 DeepSeek；DashScope 配置该模式会在启动时拒绝，以避免无证据的
continuation 假设。同步本地 HTTP Stub 验证 Spring AI 的 metadata 到 wire `reasoning_content` 映射；SSE 单元测试验证
跨 chunk 的 `<think>` 不可见；ToolLoop 测试验证取消不创建续轮或持久化。
