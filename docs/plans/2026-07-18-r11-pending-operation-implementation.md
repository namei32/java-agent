# R11 B2b Pending Operation 实施计划

- 状态：O1–O5 无执行底座已实现并通过聚焦 default / failure / compat；Capability 接缝与恢复仍未开始
- 日期：2026-07-18
- 前置：B2a `7200978`、绑定标识修复 `d76466c`
- Spec：[R11 B2b Pending Operation 设计](../specs/2026-07-18-r11-pending-operation-design.md)

## 切片

1. **O1 Contract Fixture（RED → GREEN，第三段）。** Java-owned `tools/pending-operation-v1.json` 现有 34 个 Case：原有不透明 Ref、状态优先级、AES-GCM、v1→v2、原子 Store 与篡改失败关闭外，新增已批准的一次性消费、未批准拒绝、到期优先、重复/并发 Reservation 非执行权、Reservation 失败回滚、五项 Ledger 状态与两项 Session 条件提交。仍未冻结重启或实际 Tool Invocation Case。
2. **O2 Application State Machine（GREEN，第一段）。** 已新增不可变 `PendingOperation`、随机 `operationRef`、有界状态转换、脱敏 `toString` 与绑定精确 Approval 的 `PendingOperationCapsule`；不接 `ChatService`、`ApprovalPort` 或任何 Invoker。
3. **O3 SQLite Schema v2（GREEN，第二段）。** `AES/GCM/NoPadding` Adapter 每次加密使用随机 96-bit nonce，AAD 绑定 Ref/Fingerprint/Capsule Version/Tool Version，认证失败统一关闭。`approval-inbox.db` 已从 v1 迁移到 v2，保留 Inbox 历史，并在一个事务写 Inbox、密文 Capsule。已实现已批准 Approval 的 CAS `CONSUMED`、Operation `CONSUMING` 和唯一 Ledger `RESERVED`：未批准、到期、重复或写入失败不产生第二个 Reservation，更不调用 Tool。
4. **O4 Session 条件提交（GREEN）。** 已扩展 `SessionRepository` 为 `appendTurnIfNextSequence`，SQLite 使用 Cursor Compare-And-Set 原子追加完整 Turn；旧 Revision 和不存在的非零 Revision 不写入数据或空 Session。此 Port 尚未接 Chat 或 Tool。
5. **O5 Durable Ledger 状态（GREEN，无执行）。** 已实现 `RESERVED -> RUNNING -> SUCCEEDED`、`RESERVED -> FAILED`、`RESERVED|RUNNING -> UNKNOWN` 和已知成功后的 `COMMIT_UNREPORTED`。每次状态写入与 Operation 在同一立即事务中；安全 Result 使用受限编码和 `toString` 脱敏。没有 Invoker 或 Resume Worker。
6. **O6 Capability 接缝（下一步，仅 Contract）。** 设计版本化 Pending 投影、显式 Resume/Cancel API、密钥轮换、Session Revision 检查、受限 Fake Invoker 与单 Tool Capability 的连接点；没有独立批准不得注册 Bean。
7. **O7 阶段验收。** B2b 的 Capability 接缝及恢复演练完成后，才与 R11 剩余 Capability 统一运行默认、`failure`、`compat` Reactor 门禁。

## 不做

不迁移 Python 的宽松 Hook、副作用工具、真实 Telegram、远程访问、CLI+Web、前端、后台自动恢复或任何真实网络/文件/Shell/消息写入。
