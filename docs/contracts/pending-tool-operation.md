# 待审批 Tool Operation、参数胶囊与恢复安全契约

- 状态：已冻结；O1/O2 无执行状态机与 O3 的内存 AES-GCM 胶囊边界已实现并验证，耐久化与恢复未实施
- 契约版本：1（B2b）
- 日期：2026-07-18
- 阶段：R11 B2b Pending Operation
- 前置：[本地审批收件箱与待处理操作安全契约](tool-approval-inbox.md)
- 前置：[Tool 审批、副作用、幂等与沙箱安全契约](tool-approval-side-effect-safety.md)
- 关联 ADR：[ADR-0018：待审批操作使用单一隔离事务存储](../adr/0018-use-single-transaction-pending-operation-store.md)

> 本契约只冻结恢复语义与耐久化前置条件；它不启用任何真实 Tool、自动恢复 Worker、网络、文件写入、Shell、消息发送或新的公开端点。没有单独 Capability Contract 的 Tool 永远不能创建或消费 Pending Operation。

## 1. 为什么 B2a 不能直接执行

`ApprovalPort` 是同步调用；`ChatService` 在同一受 Session Gate 保护的调用栈内生成 Tool Result 并提交 Conversation。`ActiveTurnRegistry` 则是单 JVM 的观测与取消表，两者都不能跨重启保存参数或证明一次性执行。

因此 B2a 的 `APPROVED` 只是 Inbox 的终态，不是执行授权。B2b 必须以一个独立、耐久的 Operation 表示模型已提出但尚未执行的精确调用；HTTP/Channel 原 Turn 不得挂起，模型也不得重新生成 Tool Arguments 来“续跑”。

## 2. 隔离存储与参数胶囊

`approval-inbox.db` 在 B2b 升级为唯一的隔离 Tool Operation 事务域（Schema v2）：Approval、Pending Operation、AES-256-GCM 参数胶囊、消费记录、Side Effect Ledger Reservation 与稳定审计均在同一 SQLite 事务内更新。它继续与 `sessions.db`、Channel Ledger、Memory 和 Workspace 数据库隔离。

参数胶囊只包含恢复所必需的不可变数据：版本化 canonical Tool Arguments、精确 Tool Name/Version、Provider Call ID、原始 Session ID、预期 `nextSequence`、Turn ID、Approval ID/Fingerprint、幂等键及受信执行边界版本。它不得包含模型消息、Summary、Provider Secret、Operator Token、异常正文或 Tool Result。

- Capsule 使用每条记录独立随机 96-bit nonce 的 AES-256-GCM；AAD 至少绑定 `operationRef`、Approval Fingerprint、Capsule Version 与 Tool Version。
- 密钥只来自一个显式配置的本机 `PendingOperationKeyProvider`，携带不可猜测的 `keyId`；默认没有该 Bean/密钥，故无法创建或消费 Operation。
- 密文、nonce、`keyId`、ciphertext version 和认证失败均不得进入 HTTP、生命周期、普通日志或 Conversation。
- 解密、AAD、canonical Arguments Hash、Fingerprint、Tool Version、风险和执行边界中任一项不匹配，操作必须终态化为 `UNKNOWN` 或 `CANCELLED`，且 Invoker 调用次数为零。

## 3. 状态机与优先级

```text
PENDING_APPROVAL -> APPROVED_PENDING_RESUME -> CONSUMING -> SUCCEEDED
                  -> DENIED                     |             -> COMMIT_UNREPORTED
                  -> EXPIRED                    -> FAILED
                  -> CANCELLED                  -> UNKNOWN
                  -> STALE_SESSION
```

`PENDING_APPROVAL` 仅能由已经完成 Schema、预算、风险与具体 Tool 领域预检的 Runtime 创建。每一转换均为 compare-and-set，终态不可重开。

优先级由同一事务中的状态与时间决定：

1. 到期或显式取消在 `CONSUMING` 前优先于迟到批准；不调用 Invoker。
2. 已提交的新 Session Turn（`nextSequence` 不再等于 Capsule 记录值）使旧 Operation 变为 `STALE_SESSION`；批准不再可消费。
3. `CONSUMING` 只可在再次验证 Approval、Capsule、Session Revision、取消标记与 Ledger 后，将 Approval 标为 `CONSUMED` 并创建 Ledger `RESERVED`。两个写入必须属于同一 SQLite 事务。
4. `RESERVED`、`RUNNING`、`UNKNOWN` 的重放绝不调用 Invoker。无法证明 Invoker 未越过副作用边界时一律 `UNKNOWN`。
5. 进程重启不会自动恢复或调用工具；只允许未来显式、认证后的本机恢复动作竞争 `APPROVED_PENDING_RESUME`，并重复第 3 条检查。

取消、过期、新 Turn 与重启都不是“重新向模型提问”的理由。它们只产生稳定、无参数的终态投影。

## 4. Conversation 与 Channel 边界

初始 Chat/Channel Turn 在写入 Pending Operation 后必须以版本化、无敏感字段的 `PENDING_APPROVAL` 投影结束；不得保持 HTTP、SSE、Telegram 或模型连接。该投影不是 Tool Result，不能诱导模型继续同一 Tool Loop。

`sessions.db` 不参与 Tool Operation 事务。恢复时必须用 `SessionRepository.appendTurnIfNextSequence`（B2b 新 Port）作条件提交：

- 条件提交前发现 Session Revision 已变化：不执行或不再提交原 Operation；操作为 `STALE_SESSION` 或 `COMMIT_UNREPORTED`，绝不重放副作用。
- Side Effect 已 `SUCCEEDED` 但条件 Conversation 提交失败：记录 `COMMIT_UNREPORTED`，保留安全结果与 Ledger；不得再次调用 Tool 或伪造 Conversation 已提交。
- 取消或 `UNKNOWN`：不提交 User/Assistant Turn，也不把参数或异常正文写进 Conversation。

这意味着 B2b 不承诺跨 `approval-inbox.db` 与 `sessions.db` 的全局事务；它以“副作用最多一次、Conversation 最多一次、结果可恢复但不重放”为正确性边界。

## 5. API、身份与默认关闭

沿用 B2a 的 Loopback Operator Session 仅记录匿名 `actorRef`，不是 RBAC 或人类身份。B2b 在没有独立版本化 API Contract 前不新增 Resume、Cancel、结果查询或批量操作路由；现有决定端点也不得自动调用 Invoker。

所有模式默认关闭。仅当未来的 Capability Contract、Pending Operation Key、Schema v2、Durable Ledger、Session 条件提交与受限 Loopback API 同时可用时，某个具体 Tool 才可创建 Pending Operation。否则创建与消费均 Fail Closed，Bootstrap 继续提供 `DenyAllApprovalPort`、`SideEffectLedger.unavailable()` 与 `AGENT_TOOL_MODE=DISABLED`。

## 6. B2b 验收顺序

1. Java-owned Fixture 已固定 18 个 Case：不透明 Ref、状态优先级，以及 Capsule AAD/篡改、引用替换和未知密钥的失败关闭；后续扩展状态 CAS、重启、Ledger `UNKNOWN`、条件 Conversation 提交与零 Invoker。
2. 已实现无执行的 Operation 状态机和内存 AES-256-GCM Capsule；下一步是 SQLite v2 迁移与单事务 Store，并测试所有失败路径。
3. 再实现 `appendTurnIfNextSequence` 的 SQLite 条件提交；不接 Chat 或真实 Tool。
4. 只有在某个单独批准的 Capability Contract 中，才将其接到创建/恢复路径并以本地 Fake Invoker 演练。
5. R11 所有 B 阶段完成后，统一运行默认、`failure`、`compat` 三套 Reactor 门禁。
