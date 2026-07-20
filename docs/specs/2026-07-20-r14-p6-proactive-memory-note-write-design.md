# R14 P6 Java-native NOTE 写入演练设计

- 状态：已冻结，待 TDD 实现
- 日期：2026-07-20
- 依据：[P6 Contract](../contracts/r14-p6-approved-proactive-memory-note-write-rehearsal.md)

## 设计目标

在不打开任何生产入口的前提下，验证“获批的主动候选”能安全地落到 Java-owned SQLite Mutation Ledger 与
`memory_items` 事务。它验证的是写入与恢复边界，不是自动 Memory/Optimizer 运行时。

## 结构

```text
FIXED_LOCAL Candidate
  -> P6 Pending / Approval / AES-GCM Capsule / Anchor
  -> explicit test-only Recovery
  -> P6 dedicated MemoryWrite capability
  -> Fake Embedding + JdbcJavaMemoryStore (temporary agent-memory.db)
  -> safe CREATED | REINFORCED receipt
```

P6 Operation 与 P3 Operation 完全独立。P6 不接受 Session、路径、模型名、正文类型或任意参数；Capsule 解密后的敏感正文
只能在专用 Capability 中短暂存在。普通 `MemoryWriteService` 不参与，因为它的 Session 输入和 `EXPLICIT_API` provenance
不符合 P6。

## 失败闭环

| 边界 | 行为 |
| --- | --- |
| 未批准、到期、取消、篡改、竞争失败 | 零 Embedding、零 SQLite DML。 |
| 已存在同一 Mutation Ledger | 安全重放 Receipt，零 Embedding、零 DML。 |
| Embedding 失败 | 尚未开始 SQLite 写事务，P6 固化安全失败。 |
| SQLite/DML 结果不确定 | P6 固化 `UNKNOWN`，禁止自动重试。 |
| Memory 事务内失败 | `memory_items` 与 Mutation Ledger 同时回滚。 |
| Anchor 提交失败 | `COMMIT_UNREPORTED`，不得再次写入。 |
| 测试结束 | 关闭连接、删除临时数据库及其 WAL/SHM。 |

## 非目标

不接线 Scheduler、P2 Source、Tool、Chat、HTTP、Loopback、Worker、真实 Embedding、生产配置或外部网络；不处理
HyDE、关键词、RRF、合并、软失效、删除、分类、跨用户 Scope、数据迁移或长期保留。
