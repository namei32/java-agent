# R11-B2c Scope 受限 Memory Forget Capability 设计

- 状态：F1–F6 已完成；静态 Capability、认证 Capsule、Pending 创建、显式恢复、SQLite 取消、Loopback 控制面、
  默认关闭的 Servlet Bootstrap、失败/并发边界与 24 Case 控制面 Fixture 均已通过聚焦测试和三套完整阶段门禁；
  Catalog/Chat 仍未完成
- 日期：2026-07-19
- Contract：[获批的 Scope 受限 Memory Forget Capability](../contracts/approved-scope-bound-memory-forget.md)
- ADR：[ADR-0035](../adr/0035-use-scope-bound-soft-supersede-for-memory-forget.md)

## 1. 纵向边界

```text
Tool Catalog -> forget_memory Pending 投影 -> Approval Inbox
    -> authenticated local Resume -> Capability -> Scope-bound SoftForgetPort
    -> agent-memory.db V2 -> safe result -> Session Anchor CAS
```

Tool 调用本身不执行写入。没有明确模式、Approval、Reservation 和 Anchor 的精确绑定，或任何一项恢复
检查失败时，调用计数必须为零。Resume 不接收 Tool 参数；它只消费已认证加密 Capsule 中的规范化 `ids`。

F3 的生产恢复器不是泛化 Tool 执行器：它只接受 `forget_memory` 的静态 Descriptor，并仅从已认证
`PendingOperationStore` 边界取得短生命周期 Capsule。它以 Operation Ref 派生内部 Mutation Key，以 Capsule
的 Session 派生 Scope，并以固定、无参数的 Assistant 完成投影提交 Anchor；Memory 的安全 JSON Result 只进入
已受 Ledger 大小限制的安全结果字段。

F3 的创建器先完成同库的 Inbox/Operation/Capsule 原子写入，再以 Session Cursor CAS 写 Pending Turn/Anchor，
不宣称跨库事务。Session CAS 不成功或 Session 写入故障后，创建器只会尝试将仍未消费的 Operation 固化为
`STALE_SESSION`；它不会创建第二个 Operation、重放写入或执行 Capability。Store 创建失败则 Session 零写入。

## 2. Kernel 模型

- `MemoryLifecycleState`: `ACTIVE`、`SUPERSEDED`；它是 `MemoryItem` 的不可变字段，`toString()` 不输出
  ID、正文、Scope 或 Hash。
- `MemorySoftForgetCommand`: Scope、不可调用方控制的操作键、规范化 ID 列表和时间；构造时拒绝空、重复或
  非法 ID，空列表在 Capability 前短路。
- `MemorySoftForgetResult`: requested/superseded/missing 三个有序 ID 列表，负责尺寸、一致性和脱敏 JSON
  投影；没有 `MemoryItem` 或内容字段。
- `MemoryWritePort` 增加显式 Soft-Forgot Port，而不改变 HTTP 物理 `delete` 的含义。Capability 持有的
  Invoker 只能调用该 Port，不能得到任意 SQL、Session 或 Query 权限。

## 3. SQLite V2

`memory_items.status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUPERSEDED'))`，并建立
`(scope_binding, status, updated_at DESC, id ASC)` 索引。所有正常 item、检索和列表 SQL 显式附带
`status='ACTIVE'`。

新增 `memory_mutation_items`：`mutation_id`、`ordinal`、`item_id`、`result_status`，主键为
`(mutation_id, ordinal)`，且 `ordinal` 保留请求顺序。它仅供 FORGET 的 idempotent replay；外键、索引、
列顺序、无 View/Trigger/未知表均进入严格 V2 validator。V1→V2 由新的 schema class 管理，Initializer
只允许 0→1→2 的单调路径，并为每一次实际升级创建独立备份。

## 4. Bootstrap 与控制面

新增严格枚举 `MemoryForgetCapabilityMode { DISABLED, LOOPBACK_APPROVAL }`。配置类不得用宽松字符串
或默认兜底开启。Bean 仅在 Servlet 运行时且 Contract 的四项前置均满足时装配；否则 Tool Catalog、Pending
Store 开启状态和 Controller 映射不能间接使 Tool 可见。`capsule-key-id` 与规范标准 Base64 的 AES-256
`capsule-key-base64` 只在显式 `LOOPBACK_APPROVAL` 下解码，所有 `toString()`、审计与响应均排除密钥。

既有 `pending-operations/{operationRef}` Loopback Contract 的 Resume/Cancel/Status 消息将由这个首个
Capability 实现消费，复用其认证、Host、Origin、`no-store`、审计和严格无 Body/Query 约束。它不是
R13 Dashboard/Web、CLI 或远程 API 的解冻；不会增加 SSE、Worker 或自动 Resume。

F4 的成功响应和 Status 一律是 `{schemaVersion,state,updatedAt}`；不返回 Tool、Approval、Capsule、Ledger
结果、Session 或 Conversation。Resume 内联调用 F3 的显式恢复器，但没有后台重放；Cancel 先取消未运行的
Operation/Approval，再条件取消 Anchor，第二步故障仍保持零执行权。请求形状错误、缺失、不可恢复、UNKNOWN、
运行中取消和存储故障使用 Pending Recovery Contract 中固定的 400/404/409/503 稳定码。

## 5. 故障模型

| 位置 | 处理 |
| --- | --- |
| 输入 Schema/ID 规范化 | Tool 参数拒绝；无 Pending/Approval/写入 |
| Schema/backup/SQLite | Fail Closed；无部分迁移或部分批量状态 |
| 未批准、过期、取消、Anchor 过时 | 稳定终态；零 Invoker |
| 并发 Resume | 一个 Reservation 获胜；其他零 Invoker |
| Invoker 结果不确定 | `UNKNOWN`；绝不自动重放 |
| 成功后 Conversation CAS 失败 | `COMMIT_UNREPORTED`；保留安全 Ledger result，绝不重放 |

## 6. 非目标

没有 Python DB 兼容、跨 Scope Forget、正文回送、自动记忆、`memorize`、真实 Provider/Embedding、
真实 Telegram、远程访问、前端、CLI+Web、文件/Shell/网络写入或生产 Smoke。
