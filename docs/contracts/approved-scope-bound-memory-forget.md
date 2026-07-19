# 获批的 Scope 受限 Memory Forget Capability 契约

- 状态：已冻结，实施中；路径 A 于 2026-07-19 获用户批准
- 契约版本：1
- 阶段：R11 B2c
- 前置：[Pending Operation Session Anchor 与 Recovery Capability 契约](pending-operation-recovery-capability.md)
- 关联 ADR：[ADR-0035：以当前 Scope 的软失效实现获批的 Memory Forget](../adr/0035-use-scope-bound-soft-supersede-for-memory-forget.md)

> 本契约只授权 Java 专用或临时测试 Workspace 内的本地 SQLite 软失效能力。它不读取、迁移或修改
> Python `memory2.db`，不访问真实网络、Telegram、文件、Shell、前端或生产数据；默认仍为关闭。

## 1. 固定 Tool 与可见性

Capability 是静态的 `forget_memory`，版本 `java-memory-forget-v1`，风险 `WRITE`。名称、版本、
Schema、摘要、幂等派生和执行边界不得由模型、Plugin、MCP 或配置字符串动态构造。

只有以下条件同时成立时，它才可由当前 Turn 的 Catalog 看见：

1. `agent.memory.mode=JAVA_NATIVE`；
2. `agent.control-plane.mode=LOOPBACK`、Approval Inbox 为 `LOOPBACK`，并继续满足既有 Loopback
   Token/Host/Origin 边界；
3. `agent.capabilities.memory-forget.mode=LOOPBACK_APPROVAL`；
4. Java Memory Schema V2、Pending Store、Session Anchor、密钥和显式 Capability Bean 都健康。

Capability 只在 Servlet Runtime 运行。开启时还必须提供严格格式的
`agent.capabilities.memory-forget.capsule-key-id` 与规范标准 Base64 的 32-byte
`agent.capabilities.memory-forget.capsule-key-base64`；它们只用于 AES-256-GCM Capsule 边界，绝不进入日志、
审计、HTTP 响应、Tool Schema 或 `toString()`。

`DISABLED` 是该新属性、模板和测试外运行的唯一默认值。任一条件不满足时，本 Capability 为零 Catalog
注册、零 Pending Operation、零 Approval、零 Invoker 调用和零路由；Memory/Inbox 自身按其既有、独立配置
保持行为。本契约只复用既有本机 Pending Operation 控制路径；不创建 Dashboard、CLI、前端、远程监听或后台
自动恢复。

## 2. 输入规范化与安全结果

Tool Schema 严格为：

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {"ids": {"type": "array", "items": {"type": "string"}}},
  "required": ["ids"]
}
```

每个字符串以 Java `String.strip()` 规范化；空值丢弃，重复值保留首次出现的位置。不得接受 Session、
Scope、正文、Embedding、Request ID、Approval、Idempotency Key、路径或其他字段。空规范化列表在 Tool
已可见时立即返回成功，且不创建 Pending/Approval/Reservation。

最终安全成功结果固定为：

```json
{
  "requested_ids": ["id-a", "id-b"],
  "superseded_ids": ["id-a"],
  "missing_ids": ["id-b"],
  "count": 1
}
```

三个 ID 数组均保持规范化请求的相对顺序；`count == superseded_ids.length`。同 Scope 内已是
`SUPERSEDED` 的条目仍归入 `superseded_ids`，使重复获批请求可稳定投影。不存在、跨 Scope、格式通过但
无法匹配的 ID 都只归入 `missing_ids`。结果不得包含 Python 的 `items` 字段，也不得包含正文、类型、
Embedding、Hash、Scope、Session、Capsule、Approval、Ledger、异常正文或数据库路径。

这刻意保留与 Python Tool 的两项安全差异：Java 只作用于当前 Session Scope，且不回传记忆内容；因此它
只对齐已批准的行为子集，不声称逐字节兼容。

## 3. 状态、查询与重激活

Memory Schema V2 的每条记录只有 `ACTIVE` 或 `SUPERSEDED`：

- Forget 在单个当前 Scope 事务中把命中的条目标为 `SUPERSEDED`，更新时间和 Revision；不删除正文、
  向量或现有审计。
- 常规 Memory HTTP 查看、语义检索、Hotness/Candidate 计数和 Context 注入都只读取 `ACTIVE`。
  `SUPERSEDED` 不能通过召回、Prompt 或 Tool 安全结果泄漏。
- 同 Scope、同 Type、同 Content Hash 的显式 UPSERT 命中 `SUPERSEDED` 时重新变为 `ACTIVE`，并执行现有
  强化/Revision 规则。这与 Python `MemoryStore2.upsert_item` 的再激活行为对齐。
- 既有显式 HTTP `DELETE` 仍是物理删除；它需要当前 Scope 和其自己的 HTTP 幂等键，不能当成 Forget
  或被 Tool 调用。

## 4. 幂等、Schema 与恢复

Forget 使用 Capability 从已认证 Capsule 派生的内部操作键，调用方不可提交。V2 在 `memory_mutations`
中记录 `FORGET` 的无正文摘要，并在 `memory_mutation_items` 中按请求序号记录 `item_id` 与
`SUPERSEDED`/`MISSING` 结果；同 Scope+操作键+相同参数 Hash 重放必须返回原投影，参数不同则
Fail Closed 为幂等冲突。两张表均不得保存正文、向量、原始 Session ID、Capsule 或 Provider 响应。

V1 到 V2 前必须通过 SQLite Backup API 成功创建一致性备份；迁移新增状态列、活动查询索引和结果表，
再将版本原子推进为 2。备份、DDL、校验或事务失败时不得留下部分 Schema 或 Mutation。未来版本、
未知列/表、View、Trigger 和不兼容约束均拒绝启动。

每次非空 Tool 调用先持久化加密 Capsule、Approval、Pending Operation 与 Session Anchor。只有已批准、
Anchor/Capsule 精确匹配、未过期/取消/过时、唯一 Reservation 已获得且已写 `RUNNING` 时，Capability
才调用本地 Soft-Forgot Invoker。`UNKNOWN`、`COMMIT_UNREPORTED`、取消、到期、竞争失败和新 Turn 都
零重放；成功的 Conversation CAS 失败只转 `COMMIT_UNREPORTED`。

Recovery Coordinator 只能通过 `PendingOperationStore` 的受限读取取得已解密且已重新认证的
`PendingOperationCapsule`：Adapter 必须先重建完整 Operation、认证密文 AAD 并验证 Capsule 绑定，再把
Capsule 短暂交给获批 Capability。Coordinator、Controller 和 Capability 均不能读取密文、Key、Nonce 或任意
持久化参数列；认证失败与缺失均不得获得执行权。

创建非空请求时，两库不伪装为全局事务：先在 `approval-inbox.db` 原子创建 Approval、Operation 与加密
Capsule，再用预期 Cursor 在 `sessions.db` 原子追加 Pending Turn/Anchor。前一写入失败时不得触及 Session；
后一 CAS 返回 `false` 或抛出异常时，只能把尚未消费的 Operation 固化为 `STALE_SESSION`，不得留下可恢复的
执行权或重试创建。空规范化请求在任一 SQLite、审批、Anchor 或生成器被访问前直接返回既定安全成功结果。

## 5. 验收

Java-owned `tools/memory-forget-capability-v1.json` 将固定模式、Schema、规范化、Scope 隐藏、状态过滤、
再激活、Schema V1→V2、幂等、Approval/Anchor/Reservation、并发单获胜者、取消/过期、新 Turn、
`UNKNOWN` 与 `COMMIT_UNREPORTED`。所有测试仅使用临时 Java SQLite、固定时钟、Fake Approval 与 Fake
Invoker；实施完成后才在阶段末执行默认、`failure` 与 `compat` 三套完整门禁。
