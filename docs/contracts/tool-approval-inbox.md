# 本地审批收件箱与待处理操作安全契约

- 状态：已实现并验证；B2a Inbox Foundation
- 契约版本：1
- 日期：2026-07-18
- 阶段：R11 B2a Approval Inbox Foundation
- 前置契约：[Tool 审批、副作用、幂等与沙箱安全契约](tool-approval-side-effect-safety.md)
- 前置契约：[Loopback 控制面、安全状态、事件流与活动 Turn 取消契约](loopback-control-plane.md)
- 关联 ADR：[ADR-0017：审批收件箱使用独立耐久边界](../adr/0017-isolate-durable-approval-inbox.md)

> 本契约只授权默认关闭的本地审批收件箱、一次性决定和耐久审计投影。它不授权任何真实副作用工具、审批后的自动执行、挂起 Chat HTTP、跨重启恢复 Tool 参数、远程访问或人类身份/RBAC。

## 1. 目标和刻意边界

R3.2 已有不可变 `ApprovalRequest`、指纹、同步 `ApprovalPort` 和 Side Effect Ledger 框架；它只会在同一调用栈中得到决定，生产 Bootstrap 固定 `DenyAll`。R6.5 的 Loopback 控制面有临时 Operator Session 和活动 Turn 取消，但没有耐久身份、审批记录或恢复语义。

B2a 建立一个可由本机 Loopback Operator 查看并一次性决定的耐久收件箱，以便后续 Capability Contract 能在不重写身份、指纹和并发规则的情况下接入。它不能被误认为“已经可以批准并执行工具”。

固定非目标：

- 不保存 Tool Arguments、原始 Session/Turn/Call ID、模型消息、结果、Provider 字段、密钥、路径或异常正文。
- 不暂停 HTTP 请求，不保持模型连接，不把 `APPROVED` 自动回送给模型，也不调用 Tool Invoker。
- 不把记录附着到 `ActiveTurnRegistry`；该 Registry 是易失、单 JVM、只代表存活 Telegram Turn 的观测对象。
- 不将审批库合并到 `sessions.db`、Channel Ledger 或 Java Memory 数据库；不修改既有 Schema。
- 不提供公网/LAN、Cookie、Token URL、CLI+Web、Dashboard 前端、RBAC、用户账户、自动批准、批量批准或“始终允许”。

## 2. 启动与存储边界

新增独立配置 `agent.approval-inbox.mode=DISABLED|LOOPBACK`，默认 `DISABLED`，枚举大小写严格。

| 模式 | 行为 |
| --- | --- |
| `DISABLED` | 不创建数据库、Repository、控制器、路由或后台线程；所有 Tool Runtime 继续使用 `DenyAllApprovalPort`。 |
| `LOOPBACK` | 只在已有 `agent.control-plane.mode=LOOPBACK` 且 Servlet Loopback Guard 成功时创建收件箱。 |

`LOOPBACK` 必须使用 `${agent.workspace}/approval-inbox.db` 的独立 SQLite Schema。SQLite 初始化、文件创建、Schema 校验或事务失败时启动失败；不得降级为内存收件箱或静默 `DenyAll`。数据库文件只保存本契约第 3 节的安全投影；B2a 固定最多 64 条记录且不做自动删除，保留与清理策略留给 Pending Operation Contract，避免尚无恢复语义时错误丢失决定。

启用 Approval Inbox 不等于启用 `APPROVAL_REQUIRED`，更不等于注册副作用 Tool。Bootstrap 在没有后续 Pending Operation Executor、Durable Side Effect Ledger 和逐 Tool Capability Contract 时，仍不得注册任何非只读 Tool。

## 3. 耐久记录与公开投影

内部记录以 Runtime 生成的 `approvalId` 为唯一键；控制 API 只暴露不可从 `approvalId` 推导的随机 `approvalRef`。记录至少包含：

| 字段 | 是否向 Operator 返回 | 规则 |
| --- | --- | ---|
| `approvalId`、Fingerprint、Arguments Hash、Idempotency Key | 否 | 仅耐久绑定/一致性检查，绝不在 HTTP、日志或普通审计中返回。 |
| `approvalRef` | 是 | 至少 128 bit Base64URL 随机引用，不等于 Tool 或 Turn 标识。 |
| Tool 名称、版本、有效风险 | 是 | 来自 Runtime 已验证定义；名称/版本受 Tool Contract 约束。 |
| `summary` | 是 | 仅已被具体 Tool Contract 脱敏的最小摘要；不得是模型输入或执行参数。B2a 没有真实 Tool，因此仅 Fixture/Test 使用安全固定摘要。 |
| `issuedAt`、`expiresAt`、终态决定时间 | 是 | UTC `Instant`，到期时间继承不可变 Request。 |
| 状态、决定 Actor Reference | 状态是；Actor 否 | Actor Reference 只作受限审计，不返回 API。 |

所有公开 JSON 为 `schemaVersion: 1`，字段严格区分大小写。列表按 `issuedAt`、`approvalRef` 升序；不支持分页、查询过滤、排序字段或客户端提供状态。B2a 的收件箱硬上限为 64 条，因此列表不会截断；未来 Runtime 尝试创建第 65 条时必须 Fail Closed 并产生稳定 `APPROVAL_INBOX_CAPACITY_EXCEEDED`，而非淘汰或伪装完整。

## 4. 状态机与原子性

```text
PENDING -> APPROVED
        -> DENIED
        -> EXPIRED
        -> CANCELLED
```

- 只有 Runtime 可创建 `PENDING`，且必须同时保存原始 `ApprovalRequest` 的受限绑定字段和随机 `approvalRef`。
- `PENDING` 仅能由一次原子 Compare-And-Set 变为一个终态；同一 `approvalRef` 的并发 Resolve 最多一方成功。
- 任一读、列举或 Resolve 操作先惰性将已过期的 `PENDING` 原子标为 `EXPIRED`；不得由客户端声称过期。
- `APPROVED`、`DENIED`、`EXPIRED`、`CANCELLED` 均不可修改、删除、重开或再次审批。
- 未知引用、无效格式、已终态或已过期不提供可用于枚举的内部差异；认证后的 API 可区分 `NOT_FOUND`、`ALREADY_RESOLVED`、`EXPIRED`，但正文永不包含隐藏绑定字段。
- 任何 SQLite 写入失败返回可审计的稳定不可用状态，且不得产生内存成功结果。

## 5. Loopback Operator API

所有端点复用 R6.5 Loopback Guard、`Authorization: Bearer`、Request ID、`no-store`、无 CORS 和安全审计。`OperatorSessionPrincipal.actorRef` 仅记为本次决定的匿名来源；它不是人类账户、角色或跨 Session 身份声明。创建新 Operator Session 不会继承旧 Session 的授权，也不会使旧决定失效。

```text
GET  /api/v1/control/approvals
POST /api/v1/control/approvals/{approvalRef}/decisions
```

`GET` 返回最多 64 条未超过保留期的安全投影，包含终态和 `PENDING`。`POST` 必须有且只有下列 JSON：

```json
{"schemaVersion":1,"decision":"APPROVED"}
```

唯一允许决定是 `APPROVED` 与 `DENIED`；`EXPIRED` 和 `CANCELLED` 只能由 Runtime 产生。Decision Body 的 UTF-8 上限为 128 bytes；未知字段、小写枚举、Body 缺失、超限正文、Query 参数或 Path 无效一律 `400 APPROVAL_REQUEST_INVALID`。成功首次决定返回 `200` 的新安全投影；终态竞态返回 `409 APPROVAL_ALREADY_RESOLVED`；已过期返回 `409 APPROVAL_EXPIRED`；未知 `approvalRef` 返回 `404 APPROVAL_NOT_FOUND`。

审计只记录动作、稳定结果码、Request ID、当前 `actorRef`、`approvalRef` 和计数；不得记录 Summary、Tool、风险、Fingerprint、任何哈希、决定 Body 或异常细节。

## 6. 与现有 Approval Framework 的接缝

`ApprovalInbox` 是新 Application Port，而不是 `ApprovalPort` 的替代。B2a 只验证请求可被耐久创建和本机 Operator 一次性决定。`ToolApprovalGate`、`SideEffectBatchCoordinator` 与 `DenyAllApprovalPort` 在 B2a 期间保持语义不变。

后续 B2b 必须先冻结 Pending Operation Contract，才可将一个 `ApprovalRequest` 写入收件箱。该契约至少需要明确：

1. 发起 Turn 如何安全结束或返回“待审批”，且不挂起 HTTP。
2. 恢复执行如何获得经加密/隔离保护的不可变参数，而不是从公开 Summary 或模型重生成。
3. Turn 取消、新 Turn、过期、重启、重复审批与 Ledger `UNKNOWN` 的优先级。
4. Approval Decision 如何与原指纹、一次性消费、Durable Ledger Reservation 和 Conversation 提交原子衔接。

在 B2b 和任一逐 Tool Capability Contract 完成前，`APPROVED` 仅是耐久收件箱状态，**没有执行权**。

## 7. 验收门禁

Java-owned Fixture 必须覆盖至少：默认 Disabled 零文件/零路由、Loopback 前置、严格请求 JSON、随机引用隔离、持久重启、并发双 Resolve、过期竞争、不可变终态、错误投影、Actor 不回显、无原始绑定字段、事务失败 Fail Closed 和 B2a 不调用 Invoker。

B2a 完成时运行聚焦默认、`failure`、`compat`；R11 全部 B 阶段完成后才运行 Reactor 三套严格门禁。任何真实副作用前仍需独立 Tool Capability Contract、审查和明确授权。
