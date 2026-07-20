# R14 P6 Java-native NOTE 写入演练实施计划

- 状态：Contract 已冻结；尚未开始实现
- 日期：2026-07-20
- 目标：仅在临时 Java SQLite 中验证经审批的单个 `NOTE` 写入、幂等、恢复和回退语义。

## 实施顺序

1. **P6-A Fixture（RED）**：增加不少于 30 个 `r14-proactive-memory-note-write-v1` Case 与 Manifest Hash，固定 Scope、
   保留期、Approval、Receipt、`UNKNOWN`、回退和禁止项。
2. **P6-B Kernel/Application 值对象（RED → GREEN）**：新增独立 P6 Capability、Operation、Anchor、Capsule、
   安全 Receipt 与专用 Port；禁止复用 P3 名称或伪造 Session。
3. **P6-C 专用写入 Capability（RED → GREEN）**：以固定 Clock/ID/Fake Embedding 构造 `MemoryScope`、Memory V1
   参数哈希与 request ID，先查 Mutation Ledger，再单次调用 `MemoryWritePort`。
4. **P6-D 临时 SQLite 演练（RED → GREEN）**：只用 `@TempDir`、`JavaMemorySchemaInitializer` 和
   `JdbcJavaMemoryStore` 验证真实 `CREATED`/`REINFORCED`、幂等与原子回滚；测试结束删除库、WAL 和 SHM。
5. **P6-E 恢复失败闭环（RED → GREEN）**：覆盖批准/到期/取消/篡改/并发、Embedding/DML/Audit/Ledger/Anchor 失败、
   `UNKNOWN`、`COMMIT_UNREPORTED` 与零自动重放。
6. **P6-F 审计与阶段门禁**：源码扫描确认无 Bootstrap/HTTP/Tool/Worker/网络/真实 Provider，更新矩阵与路线图，运行
   `clean verify`、`-Pfailure verify`、`-Pcompat verify`。

## 暂停点

P6 完成后停止。生产 DML、真实 Workspace、真实 Embedding、自动 Candidate 消费、Tool/Catalog 接线、配置、Worker、
长期保留或恢复后自动继续都需要新的用户授权与单独 Contract。
