# R14 P6 Java-native NOTE 写入演练实施计划

- 状态：P6-A 至 P6-F 已完成；默认、`failure`、`compat` 三套阶段门禁均通过
- 日期：2026-07-20
- 目标：仅在临时 Java SQLite 中验证经审批的单个 `NOTE` 写入、幂等、恢复和回退语义。

## 实施顺序

1. **P6-A Fixture（已完成）**：已增加 32 个 `r14-proactive-memory-note-write-v1` Case、Manifest Hash 与 compat
   Consumer，固定 Scope、保留期、Approval、Receipt、`UNKNOWN`、回退和禁止项。
2. **P6-B Kernel/Application 值对象（已完成）**：已新增独立 P6 Capability、Operation、Anchor、Capsule、
   安全 Receipt 与 Pending Port；禁止复用 P3 名称或伪造 Session。
3. **P6-C 专用写入 Capability（已完成）**：固定 Clock/ID/Fake Embedding/Model，构造无 Session 的 `MemoryScope`、
   Memory V1 参数哈希与 request ID；先查 Mutation Ledger，再单次调用 `MemoryWritePort`。
4. **P6-D 临时 SQLite 演练（已完成）**：只用 `@TempDir`、`JavaMemorySchemaInitializer` 和
   `JdbcJavaMemoryStore` 验证真实 `CREATED`/`REINFORCED`、同 Operation 的 Ledger 重放、独立 Operation 的强化；
   测试结束主动删除 `memory/` 目录。Store 的既有原子回滚契约继续由 `JdbcJavaMemoryStoreTest` 覆盖。
5. **P6-E 恢复失败闭环（已完成）**：已覆盖批准/到期/取消/篡改/并发、Embedding/DML/Audit/Ledger/Anchor 失败、
   `UNKNOWN`、`COMMIT_UNREPORTED` 与零自动重放；过期 Lease 的 claim 会释放，重复检查仍安全拒绝。
6. **P6-F 审计与阶段门禁（已完成）**：源码扫描确认无 Bootstrap/HTTP/Tool/Worker/网络/真实 Provider，已更新矩阵与路线图；
   `clean verify`、`-Pfailure verify`、`-Pcompat verify` 均通过。

## 暂停点

P6 完成后停止。生产 DML、真实 Workspace、真实 Embedding、自动 Candidate 消费、Tool/Catalog 接线、配置、Worker、
长期保留或恢复后自动继续都需要新的用户授权与单独 Contract。
