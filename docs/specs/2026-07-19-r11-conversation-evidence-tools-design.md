# R11-B4 当前会话只读证据 Tool 设计

- 状态：已完成并验证
- 日期：2026-07-19
- Contract：[当前会话只读证据 Tool 契约](../contracts/read-only-conversation-evidence-tools.md)
- ADR：[ADR-0025](../adr/0025-bind-conversation-evidence-tools-to-current-turn-context.md)

```text
ChatService(sessionId)
       │
       ▼
ToolInvocationContext ── opaque current-session EvidenceScope
       │
       ▼
ToolLoop → SideEffectBatchCoordinator → ToolRegistry
       │                                  │
       │                                  ▼
       │                         ContextualTool only
       │                                  │
       ▼                                  ▼
ordinary Tool ignores context       ConversationEvidenceTool
                                           │
                                           ▼
                          ConversationEvidencePort (Kernel)
                                           │
                                           ▼
                             JdbcConversationEvidenceAdapter
                                           │
                                           ▼
                                sessions.db, current key only
```

Kernel 定义不可变 Evidence Message/Page/Query 与只读 Port；它不包含 JDBC、SQLite、Spring、模型 Schema 或原始
Session Key 的公开 JSON。SQLite Adapter 通过 prepared statement 限制 `session_key`，按 seq 查询/搜索，不创建索引。
Application 负责 opaque ID 编解码、Input Schema、JSON 投影、Fetch window 去重、16 条投影硬上限和 Search 预览。
Bootstrap 只在双重 `READ_ONLY` Mode 下创建 Adapter/Tool，并把它们登记为 Deferred Catalog 项；关闭时仅注入不执行
I/O 的 disabled Port。

通过 `ContextualTool` 避免把 Session ID 扩散到普通 Tool API。现有 `Tool.execute(arguments)` 保持二进制/源兼容；
Registry 在创建虚拟线程任务时显式选择 context-aware 重载。测试应证明普通 Tool 永远得不到 Context、同一 Tool 的
并发 Turn 不互串、取消不会提交任何数据。
