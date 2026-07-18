# R11 B2b Pending Operation 设计

- 状态：部分实现；无执行状态机、AES-GCM Capsule、SQLite v2 原子 Store、一次性 Reservation、Ledger 状态、Session 条件提交、Anchor 安全 Result 条件提交与测试专用 Fake Capability 演练已验证；R11 分支的完整 `clean verify`、`-Pfailure verify`、`-Pcompat verify` 均通过
- 日期：2026-07-18
- Contract：[待审批 Tool Operation、参数胶囊与恢复安全契约](../contracts/pending-tool-operation.md)
- ADR：[ADR-0018：待审批操作使用单一隔离事务存储](../adr/0018-use-single-transaction-pending-operation-store.md)

## 设计边界

`PendingOperation` 是模型已提出、但尚未执行的精确 Tool 调用。它保存不透明 `operationRef`、完整
`ApprovalRequest`、预期 Session 序号和单向状态，不保存 Tool Result，也不调用 Invoker。一个 Operation 的
参数只在短暂内存中以 `PendingOperationCapsule` 表示；持久化前必须由加密 Adapter 转化为不透明密文。

当前切片没有后台恢复器、HTTP Resume/Cancel 路由或 Tool Capability Bean。Store 只在测试中由显式构造使用；
没有 Spring 接线能把 Approval、Reservation 或条件 Session 提交变成执行。

## 胶囊边界

`PendingOperationCapsule` 绑定 Session、预期序号、Turn/Call、Tool Name/Version/Risk、canonical Arguments、
Approval ID/Fingerprint、幂等键和受信执行边界版本。构造和解密后均重新计算绑定；不匹配即抛稳定的
`PendingOperationCapsuleException`，调用方只能失败关闭。

SQLite Adapter 使用 JDK `AES/GCM/NoPadding`：每次加密取得 32-byte AES Key 和随机 96-bit nonce；AAD 编码
Capsule Version、`operationRef`、Approval Fingerprint 和 Tool Version。密文认证、Key 查找、AAD 或绑定校验失败
均不返回部分 Capsule，也绝不调用 Tool。`toString()` 不输出 Session、参数、ID、nonce、ciphertext 或 Key。

## 后续落点

`approval-inbox.db` 已升级为 v2 Operation Store：同一 SQLite 事务保存新 Inbox 记录和密文 Capsule，v1 Inbox 历史
原样迁移，读取先认证 AAD、再重建完整绑定。已实现同库 CAS：只有尚未到期的 `APPROVED` 记录能变为
`CONSUMED`，同时将 Operation 标为 `CONSUMING` 并插入唯一 `RESERVED`；Reservation 写入失败会回滚前两项。
重放只返回 `ALREADY_RESERVED`，没有执行权。

`sessions.db` 已提供 `appendTurnIfNextSequence`：对匹配 Revision 的完整 User/Assistant Turn 作单事务追加；旧或
不存在的 Revision 返回 `false` 且不留下空 Session。它另有 Anchor 专用的初始 Pending Turn 原子写入和
`appendPendingResolutionIfAnchorMatches`：后者只在 Anchor、版本化安全 Assistant 投影和恢复 Cursor 精确匹配时追加
一条 Assistant Message，新 Turn 会将 Anchor 固化为 `STALE_SESSION`。两库不假装拥有全局事务，也尚未被同一个
恢复器接线。未来 Capability 必须在 Invocation 前检查 Anchor/Session，在成功后使用这个 Anchor CAS；若副作用已
成功而 Conversation 条件提交失败，状态必须是 `COMMIT_UNREPORTED`，不能重放调用。

Reservation 的状态存于同一 `approval-inbox.db`：只有 `RESERVED` 能变为 `RUNNING` 或（可证明未开始时）`FAILED`；
只有 `RUNNING` 能变为 `SUCCEEDED`；`RESERVED/RUNNING` 均可失败关闭为 `UNKNOWN`。结果序列化为有大小上限的
安全投影，`UNKNOWN` 不保存结果。实现未提供 Invoker、自动恢复或公开 API，因此这些状态只能由测试构造。

测试专用 Fake Capability 已在 `adapter-sqlite` 测试源码连接 Reservation、Ledger 与 Anchor Port，验证成功、
`UNKNOWN`、`COMMIT_UNREPORTED` 和零重放；它没有生产类型或 Bean。Resume/Cancel/Status 的 Loopback Message
Contract 也已冻结，但既有 Control Plane 不会映射这些路由。生产恢复编排、真实 Capability、Key 配置、恢复 API
实现和任何副作用 Tool 必须在独立批准后才可接线。
