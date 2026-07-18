# R12-S5 当前 Scope 只读记忆召回 Tool 设计

- 状态：已实现；默认 `DISABLED`
- 日期：2026-07-19
- Contract：[当前 Scope 只读记忆召回 Tool 契约](../contracts/read-only-memory-recall-tool.md)
- ADR：[ADR-0026](../adr/0026-restrict-memory-recall-to-current-scope-read-only-tool.md)

```text
ChatService(raw session id)
       │
       ▼
ToolInvocationContext ── private current-scope MemoryRecallScope
       │
       ▼
ToolLoop → ToolRegistry → deferred recall_memory(query, type, limit)
                              │
                              ▼
          ReadOnlyMemoryRecallService (one bounded Embedding + SemanticMemorySearch)
                              │
                              ▼
          MemoryStorePort / EmbeddingPort / Java-native agent-memory.db
```

R4.2 的 `SemanticMemoryRetrievalAdapter` 面向 Prompt 注入，只输出文本块和计数，不能直接作为 Tool Result。S5 建立独立的
`ReadOnlyMemoryRecallService`，复用 `MemorySearchRequest`、`SemanticMemorySearch` 与 Embedding 响应校验，输出
受限 `MemorySearchHit` 投影；不能为了复用而扩大现有 `MemoryRetrievalResult` 或向 Prompt 注入路径暴露 Memory ID。

原始 Session ID 仍由 ChatService 私有地转为 `MemoryScope`；Scope 只能由 package-private ContextualTool 获得。现有普通
Tool、MCP 和 Plugin 继续只能实现 `Tool.execute(arguments)`。查询过程没有 Store Mutation，Embedding 失败、候选过多、
预算超限和取消都收敛为 Tool 的稳定安全码。

Bootstrap 只在 `JAVA_NATIVE`、Tool `READ_ONLY` 和新 Mode 三重条件下创建 Query Service/Tool，并把 Tool 作为 Deferred
Builtin 放入既有 Catalog。任何 Mode 不匹配都装配 disabled sentinel，禁止因普通 Memory Context Retrieval 或 HTTP API
存在而隐式注册 Memory Tool。
