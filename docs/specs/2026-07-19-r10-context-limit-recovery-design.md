# R10-P3 上下文超限安全恢复设计

- 状态：已实现并验证

## 控制流

```text
Session Snapshot + Current User
        │
        ▼
ContextLimitRecoveryPolicy candidates
        │
        ▼
assemble(candidate) → ToolLoop
        │                  │
        │                  ├─ normal result ─────────────► append one current Turn
        │                  ├─ context limit before Tool ─► next candidate
        │                  └─ Tool/stream/cancel/other ──► existing failure path
        ▼
all candidates exhausted → original ModelContextLimitException
```

`MemoryContextService` 接受一个最低 `PromptTrimPlan`，使每一次候选在项目已有 Prompt 编译器中继续遵循预算、排序和
Section 放置规则。History 在调用方以完整轮次尾部切片，避免孤立 assistant；源 `SessionSnapshot` 永不修改。

`ToolLoop` 只把“Provider 上下文超限且尚未执行 Tool”的事实作为内部恢复信号交给 `ChatService`。该信号不携带
Provider Body、messages、Tool Call、结果、Session 或 trace；一旦有 Tool 执行，它退化回原异常。流式路径始终不产生该
信号，避免重复已发布的 Delta。

## 不变量

- Policy 与 Prompt 组装均为纯本地计算；`DISABLED` 仅保留一个原始 `FULL` 调用候选，不会创建重试候选。
- 每个候选重用同一 snapshot/current user、同一 Turn cancellation 和同一 Side Effect Context；成功只 append 一次。
- `ModelContextLimitException` 是唯一可继续条件；所有其他异常的对象、固定公开 message 与失败分类保持不变。
- 不增加数据库字段、HTTP/Channel 字段、日志 payload、Provider Option 或真实 Smoke。
