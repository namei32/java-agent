# R14 P3–P5 安全自动化设计

- 状态：已实现为离线、默认关闭的 Fake/静态 Catalog 切片
- 日期：2026-07-19
- Contract：[P3 主动记忆 Mutation](../contracts/r14-p3-approved-proactive-memory-mutation.md)、[P4 Fake Peer](../contracts/r14-p4-local-fake-peer-process.md)、[P5 受信 Catalog](../contracts/r14-p5-trusted-proactive-catalog.md)

```text
P2 private LocalProactiveCandidate             P0 PeerIdentity + static Fake Manifest
              |                                                  |
              v                                                  v
 P3 Pending/Approval/Capsule/Anchor              P4 Pending/Approval/Capsule/Anchor
              |                                                  |
              v                                                  v
  Fake Java-native Memory Mutation Port                Fake Peer Process Port
              |                                                  |
              +------------- no Bootstrap / no Worker ------------+
                                      |
                                      v
                         P5 static DEFERRED request schemas
                         (catalog only; no execution authority)
```

P3 与 P4 各自持有独立 Operation/Anchor，绝不把主动任务伪装为 Chat Session 或复用 P2 Delivery 的 Recipient。三个
切片都把敏感正文、Scope、Task、Manifest 与结果留在同包 Capsule/Port 边界；公开 Outcome、Tool Schema、Fixture expected、
审计和 `toString()` 只允许稳定状态与 opaque Ref。

P3 只把 `FIXED_LOCAL` 候选作为 `NOTE` 交给 Fake Java-native Memory Port，传递派生 Scope Hash 与固定
`fake-r14-memory-v1` 标识，但不调用模型或实际 Embedding。模型不能创建摘要、类型或 Merge Plan。P4 只调用注入 Fake
Port，代码中不出现进程或网络 API。P5 使用既有 Turn Scoped `ToolCatalog` 的 `DEFERRED` 机制，但其 Placeholder 只返回
固定不可用结果，不拥有 Producer 或副作用执行权。

所有端口失败、审计失败、Ledger/Anchor 失败都按既有副作用规则终止为 `UNKNOWN` 或 `COMMIT_UNREPORTED`；任何终态、取消、
过期或竞争失败均不可重放。静态 Peer 值对象位于 `agent-kernel`，Pending/Recovery/Port 边界位于
`agent-application`；没有 Spring、JDBC、Scheduler、Provider、网络或生产配置依赖。
