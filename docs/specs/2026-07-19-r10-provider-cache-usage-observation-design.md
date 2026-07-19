# R10-P4 Provider 缓存用量与受限观察设计

- 状态：Contract/Fixture 已冻结，TDD 实现中

```text
Spring AI ChatResponse metadata
    │  standard prompt/cache-read numbers only
    ▼
ProviderCacheUsage? ── invalid/missing ──► absent
    │
    ▼
ChatModelResponse (sync or completed stream)
    │
    ▼
turn-local collector ──► ProviderTurnUsage
    │                         (three aggregate numbers only)
    ▼
Session append succeeds
    │
    ▼
ProviderUsageObserver (default no-op, exception-isolated)
```

`ProviderCacheUsage` 是 Adapter 输出值对象，不接受可变 map 或 Provider 的 native object。`ToolLoop` 在每次收到
`ChatModelResponse` 后把它交给本 Turn collector；因此 Tool Call 前后的模型响应都会计数，但任何失败的逻辑 Turn
都不能到达发布阶段。上下文超限恢复的失败请求没有响应样本，成功候选仍属于同一个最终 Turn collector。

没有操作员可见的存储或 API：这条链只建立一个可替换的内部 Port。R13 若要接入控制面，必须另行冻结认证、保留期、
聚合范围、查询预算和禁止关联身份的 Contract；不能直接将这个 Port 绑定到 dashboard、日志或 SQLite。
