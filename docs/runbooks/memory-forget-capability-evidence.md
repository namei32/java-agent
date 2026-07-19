# Scope 受限 Memory Forget 能力运行与证据手册

- 状态：离线验证完成；生产默认关闭
- 日期：2026-07-19
- Capability：[R13-C3 Scope 受限 Memory Forget 执行契约](../contracts/r13-c3-approved-memory-forget-execution.md)
- 管理入口：[C3-M0 Memory Forget 管理入口决策门禁](../contracts/r13-c3-memory-forget-management-ingress-gate.md)

> 本手册说明已经通过离线验证的能力边界，不授权真实数据、部署、网络、渠道、前端或控制面创建入口。

## 1. 能做什么与不能做什么

`forget_memory` 是当前唯一获批的 Memory 写 Capability。它只能将**当前 Session Scope** 中、已经由 Tool/Chat
Producer 规范化的 Java Native Memory ID 标记为 `SUPERSEDED`。这是软失效，不是物理删除。

它不能：读取或修改 Python `memory2.db`、跨 Scope 操作、查看记忆正文、删除 Session/Message、自动运行、批量管理
全部 Memory，或从 HTTP/Dashboard 直接创建请求。`operationRef` 只对应已经创建的 Pending Operation，不能当成
Memory ID、Tool 参数或查询键。

## 2. 固定恢复链与责任

```text
tool_search -> Deferred forget_memory
  -> Pending + Approval + encrypted Capsule + Session Anchor
  -> local operator decision
  -> Resume obtains one Reservation
  -> MemorySoftForgetPort
  -> safe result + COMMITTED | UNKNOWN | COMMIT_UNREPORTED
```

| 组件 | 唯一责任 | 不拥有的权限 |
| --- | --- | --- |
| `MemoryForgetPendingToolset` / `MemoryForgetPendingProducer` | 搜索后展示 Tool；非空调用只创建 Pending | Approval、Invoker、Worker |
| `MemoryForgetPendingService` | 原子创建 Approval、Capsule、Pending 与 Anchor | Memory DML、自动 Resume |
| `MemoryForgetRecoveryCoordinator` | 验证 Anchor/Capsule 后取得一次 Reservation | 创建请求、重放 `UNKNOWN` |
| `MemoryForgetCapability` | 从认证 Capsule 派生当前 Scope 后调用窄口 Port | Catalog、HTTP、Session/Approval 写入 |
| `PendingOperationController` | Status / Resume / Cancel 的安全状态投影 | Tool 参数、Capsule、Memory 查询 |

## 3. 状态与人工操作

本机认证 operator 只能在既有 Approval Inbox 中做审批决定，并对已有 `operationRef` 请求状态、Resume 或 Cancel。
Approval 不是自动执行权：只有未过期 Approval、匹配 Anchor、完整 Capsule 和独占 Reservation 同时成立，才可到达
`MemorySoftForgetPort`。

- `CANCELLED`、`DENIED`、`EXPIRED`、`STALE_SESSION`：不可恢复、零 Invoker。
- `UNKNOWN`：执行结果不确定，必须人工处置；不得自动重放。
- `COMMIT_UNREPORTED`：副作用安全结果已持久化但 Session 投影提交未报告；不得重放。

生产默认仍为 `DISABLED`。即使显式满足既有 Loopback/Servlet/Bearer 前置，也不因本手册新增 Route、Worker、数据源
或外部 I/O。

## 4. 版本化证据索引

| Fixture | 验证责任 | 消费测试 |
| --- | --- | --- |
| `tools/memory-forget-capability-v1.json` | 参数规范化、Scope 隐藏、安全结果、幂等与不确定语义 | `MemoryForgetContractFixtureTest` |
| `tools/memory-forget-pending-producer-v1.json` | 搜索解锁、空输入和非空输入只创建 Pending、零 Invoker | `MemoryForgetPendingToolsetFixtureTest` |
| `tools/pending-operation-v1.json` | Capsule、Approval、Reservation、Ledger、Anchor 和状态优先级 | `GoldenManifestTest` 与 Pending Store 测试 |
| `control-plane/pending-recovery-control-v1.json` | Loopback Status/Resume/Cancel、默认拒绝、零重放 | `PendingRecoveryControlGoldenTest` |

2026-07-19 已执行 54 个聚焦测试，覆盖 Kernel/Fixture、Producer、Recovery、临时 Java SQLite、Bootstrap 与控制面。
其中控制面 Fixture 消费 24 个 Case。所有测试只使用临时 Java SQLite、固定时钟、Fake Approval 或 Fake Invoker。

## 5. 变更与事件处置

任何要求直接创建 Forget、使用原始 Memory/Session/Scope 标识、读取正文、接线真实数据源或开启自动恢复的需求，必须
先推翻 C3-M0，并完成新的目标 Ref、请求表面、数据权限、RED Fixture 与阶段门禁。不得扩展 `operationRef`、
`detailRef`、`historyRef` 或 cursor 来规避该流程。

发生异常时，优先保留 `UNKNOWN` 或 `COMMIT_UNREPORTED`；不要通过重试、手写 SQL、修改 Capsule 或直接调用
`MemorySoftForgetPort` 来“修复”状态。真实运行、备份、恢复、数据排查和部署均需要独立操作授权。
