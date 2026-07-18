# R11 B2b Pending Operation 实施计划

- 状态：O1/O2 无执行状态机与 O3 的 AES-GCM Capsule/v2 Store 已实现并通过聚焦 default / failure / compat；Approval 消费、条件提交与恢复尚未开始
- 日期：2026-07-18
- 前置：B2a `7200978`、绑定标识修复 `d76466c`
- Spec：[R11 B2b Pending Operation 设计](../specs/2026-07-18-r11-pending-operation-design.md)

## 切片

1. **O1 Contract Fixture（RED → GREEN，第一段）。** 已新建 Java-owned `tools/pending-operation-v1.json` 的 21 个 Case，覆盖不透明 Ref、创建、批准、取消/到期/新 Turn 优先级、消费前拒绝执行、`UNKNOWN`、`COMMIT_UNREPORTED`、AES-GCM round trip、密文篡改、Ref 替换、未知 Key 失败关闭，以及 v1→v2、原子 Store 和 Store 篡改失败关闭。重启、Reservation 与条件提交 Case 留给后续扩展。
2. **O2 Application State Machine（GREEN，第一段）。** 已新增不可变 `PendingOperation`、随机 `operationRef`、有界状态转换、脱敏 `toString` 与绑定精确 Approval 的 `PendingOperationCapsule`；不接 `ChatService`、`ApprovalPort` 或任何 Invoker。
3. **O3 SQLite Schema v2（GREEN，第一段）。** `AES/GCM/NoPadding` Adapter 每次加密使用随机 96-bit nonce，AAD 绑定 Ref/Fingerprint/Capsule Version/Tool Version，认证失败统一关闭。`approval-inbox.db` 已从 v1 迁移到 v2：新增 Operation/Reservation 表，既有 Inbox 历史保留；新 Store 在一个事务中写 Inbox 与密文 Capsule，读取会重新认证并验证完整绑定，插入失败回滚 Inbox。下一步实现 Approval 消费与 Reservation 单事务 CAS；没有该步骤不得执行 Tool。
4. **O4 Session 条件提交（GREEN）。** 扩展 `SessionRepository` 为 `appendTurnIfNextSequence` 并以 SQLite 原子条件更新验证新 Turn 失效；不执行 Tool。
5. **O5 Capability 接缝（仅 Contract）。** 设计版本化 Pending 投影、显式 Resume/Cancel API、密钥轮换和单 Tool Capability 的连接点；没有独立批准不得注册 Bean。
6. **O6 阶段验收。** B2b 全部实现后才与 R11 剩余 Capability 统一运行默认、`failure`、`compat` Reactor 门禁。

## 不做

不迁移 Python 的宽松 Hook、副作用工具、真实 Telegram、远程访问、CLI+Web、前端、后台自动恢复或任何真实网络/文件/Shell/消息写入。
