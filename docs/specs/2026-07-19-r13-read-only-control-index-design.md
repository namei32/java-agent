# R13-C0 Loopback 只读控制索引设计

- 状态：Contract/Fixture 已冻结；不实现 Runtime
- 关联 Contract：[R13-C0 只读控制索引](../contracts/r13-read-only-control-index.md)
- 前置：既有 Loopback Guard、Operator Session、`ActiveTurnRegistry` 与渠道健康快照

```text
future GET /api/v1/control/index
        │
        ├─ existing Loopback Guard + Bearer operator session
        │
        ▼
bounded channel/active-turn snapshots
        │  (no history, no SQLite body query)
        ▼
safe index projection + opaque cursor
```

C0 故意只固定输出 Contract。C1 只能复用已有的内存健康/活跃 Turn 投影，不能绕过现有认证去读取 Session、Message、
Memory 或任何渠道存储；不得将 Python Dashboard 的原始 `telegram:<chatId>` URL 语义带入 Java。

在 C1 之前，fixture 不对应 Controller 或可调用路径。它的作用是提前阻止“只读 Dashboard”演变成原始身份查询、历史正文
浏览或无界列表；所有写操作仍依赖 R11-B2c 的独立 Capability。
