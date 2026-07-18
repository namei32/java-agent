# R11 B2b Pending Operation 设计

- 状态：部分实现；无执行状态机与内存 AES-GCM Capsule 已验证
- 日期：2026-07-18
- Contract：[待审批 Tool Operation、参数胶囊与恢复安全契约](../contracts/pending-tool-operation.md)
- ADR：[ADR-0018：待审批操作使用单一隔离事务存储](../adr/0018-use-single-transaction-pending-operation-store.md)

## 设计边界

`PendingOperation` 是模型已提出、但尚未执行的精确 Tool 调用。它保存不透明 `operationRef`、完整
`ApprovalRequest`、预期 Session 序号和单向状态，不保存 Tool Result，也不调用 Invoker。一个 Operation 的
参数只在短暂内存中以 `PendingOperationCapsule` 表示；持久化前必须由加密 Adapter 转化为不透明密文。

当前切片没有创建 Store、后台恢复器、HTTP Resume/Cancel 路由或 Tool Capability Bean。因此即使测试中可构造
Operation，也没有生产路径能把 Approval 变成执行。

## 胶囊边界

`PendingOperationCapsule` 绑定 Session、预期序号、Turn/Call、Tool Name/Version/Risk、canonical Arguments、
Approval ID/Fingerprint、幂等键和受信执行边界版本。构造和解密后均重新计算绑定；不匹配即抛稳定的
`PendingOperationCapsuleException`，调用方只能失败关闭。

SQLite Adapter 使用 JDK `AES/GCM/NoPadding`：每次加密取得 32-byte AES Key 和随机 96-bit nonce；AAD 编码
Capsule Version、`operationRef`、Approval Fingerprint 和 Tool Version。密文认证、Key 查找、AAD 或绑定校验失败
均不返回部分 Capsule，也绝不调用 Tool。`toString()` 不输出 Session、参数、ID、nonce、ciphertext 或 Key。

## 后续落点

下一切片将 `approval-inbox.db` 升级为 v2 Operation Store：同一 SQLite 事务内保存 Capsule、CAS 消费 Approval
并建立 Ledger Reservation。随后才给 `sessions.db` 增加条件 `appendTurnIfNextSequence`；两库不假装拥有全局事务。
若副作用已成功而 Conversation 条件提交失败，状态必须是 `COMMIT_UNREPORTED`，不能重放调用。

真实 Capability、Key 配置、Ledger、恢复 API 和任何副作用 Tool 必须在独立批准后才可接线。
