# R11 O6 Pending Recovery Capability 实施计划

- 状态：A1–A5 的 Anchor 写入、安全 Result 条件提交和测试专用 Fake Capability 演练已实现并通过聚焦 default/failure/compat；生产恢复编排未开始
- 日期：2026-07-19
- Contract：[Pending Operation Session Anchor 与 Recovery Capability 契约](../contracts/pending-operation-recovery-capability.md)
- ADR：[ADR-0019：在恢复前冻结 Pending Operation 的 Session Anchor](../adr/0019-freeze-pending-operation-session-anchor-before-resume.md)

## 前置完成证据

- `cf5df99`：无执行 Pending Operation Ledger，Fixture 34 Case，聚焦 default/failure/compat 通过；
- `8c75d64`：Approval 消费、唯一 Reservation、并发单获胜者、Session `appendTurnIfNextSequence`；
- `3f90d19`、`fd794e1`：认证参数 Capsule、Schema v2 与原子创建。

## 连续 TDD 切片

1. **A1 Anchor Fixture（RED → GREEN，第五段）。** 已扩展 `pending-operation-v1` 至 54 Case，固定合法 Anchor、
   未知版本、Opaque Operation Ref、精确 Cursor、取消/新 Turn 终态、`toString` 零泄漏、初始 SQLite 原子创建、
   旧 Cursor、Anchor 插入失败回滚、安全 Result 恢复 CAS、新 Turn `STALE_SESSION`、取消、提交失败回滚和投影
   版本拒绝、不可恢复 Cursor 拒绝，以及测试专用 Fake Capability 的成功/`UNKNOWN`/`COMMIT_UNREPORTED` 零重放；
   后续补生产恢复编排的 Fixture。
2. **A2 Session Anchor Port/Model（GREEN，第二段）。** 已新增不可变 Kernel Anchor Model、Fail Closed Port 和
   Observed 转发；`JdbcSessionRepository` 提供初始写入/读取和安全 Result 条件提交。现有 Chat/Telegram/CLI 不调用。
3. **A3 SQLite Schema/迁移（GREEN，第一段）。** `sessions.db` 已创建带版本检查的 `pending_turn_anchor_schema` /
   `pending_turn_anchors`；完整初始 Turn、Pending 投影和 Anchor 同一事务写入，旧 Cursor 或 Anchor 写入失败均回滚。
   后续由恢复编排消费 Anchor 终态。
4. **A4 条件恢复提交（GREEN）。** 只支持 Anchor 已知安全 Result 投影的 CAS 追加；旧 Cursor/新 Turn 将 Anchor
   固化为 `STALE_SESSION`，取消或投影版本不匹配返回非提交，事务失败回滚 Message、Cursor 和 Anchor；不调用 Invoker。
5. **A5 Fake Capability 演练（GREEN）。** 只在 `adapter-sqlite` 测试源码显式构造的 Fake Capability 连接真实
   SQLite Reservation/Ledger/Anchor Port，验证 `RUNNING`/`SUCCEEDED`、`UNKNOWN`、`COMMIT_UNREPORTED`、
   Anchor/Operation 绑定不符时零预留/零调用，以及所有终态零重放；没有生产类型、Spring Bean、路由或 Tool 注册。
6. **A6 Loopback Contract（仅设计）。** 在 A1–A5 审查后才定义 Resume/Cancel/Status JSON；不实现真实路由。
7. **A7 阶段门禁。** A1–A6 完成后运行默认、`failure`、`compat` Reactor 全门禁与审查。

## 明确不做

不实现真实 Tool、真实 Telegram、远程 MCP、CLI+Web、前端、文件/Workspace 写入、Shell、网络、消息写入、
后台自动恢复、真实密钥配置或生产开关。任何单 Tool Capability 都需新的明确批准。
