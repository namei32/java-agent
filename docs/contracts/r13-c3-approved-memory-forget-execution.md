# R13-C3 首项受审批管理写入：Scope 受限 Memory Forget 执行契约

- 状态：已冻结；用户于 2026-07-19 选择此项为 C3 的唯一首项写 Capability。此决定**不**授权真实数据执行或部署启用。
- 契约版本：1
- Capability：`forget_memory` / `java-memory-forget-v1` / `WRITE`
- 执行边界：`memory-forget-capability-v1`
- 前置：[获批的 Scope 受限 Memory Forget Capability](approved-scope-bound-memory-forget.md)、[Pending Operation Session Anchor 与 Recovery Capability 契约](pending-operation-recovery-capability.md)
- 关联 ADR：[ADR-0038：选择 Scope 受限 Memory Forget 作为 R13-C3 的首项写 Capability](../adr/0038-select-scope-bound-memory-forget-as-first-r13-c3-write-capability.md)
- 实施计划：[R13-C3 Scope 受限 Memory Forget 实施计划](../plans/2026-07-19-r13-c3-memory-forget-implementation-plan.md)
- 管理入口门禁：[R13-C3-M0 Memory Forget 管理入口决策门禁](r13-c3-memory-forget-management-ingress-gate.md)

> C3 不以“Dashboard 管理写入”为名绕过 R11-B2c。首项且唯一被选中的动作是已经获批的
> `forget_memory`：在当前 Session Scope 内，对指定 Java Native Memory ID 批量执行软失效。它复用既有
> Pending/Approval/Capsule/Reservation/Ledger/Anchor 恢复链，不新增 HTTP 写入入口、Worker 或自动执行。

## 1. 选择范围

Capability 的静态身份、输入与副作用固定如下，不能由模型、Plugin、MCP、配置字符串或控制面请求动态构造：

| 项目 | 已冻结值 |
| --- | --- |
| Tool 名称与版本 | `forget_memory` / `java-memory-forget-v1` |
| 风险 | `WRITE` |
| 唯一动作 | 仅把当前 Scope 内命中的 `ACTIVE` 或已是 `SUPERSEDED` 的条目标为 `SUPERSEDED`；不是物理删除 |
| 输入 | 严格 `{ "ids": ["..."] }`；`strip`、丢弃空值、稳定去重后使用；调用方不能传 Scope、Session、Approval、幂等键、路径、正文或结果 |
| Scope | 仅从已认证 Capsule 的 Session 派生；跨 Scope 与未知 ID 对外统一为 `missing_ids` |
| 成功投影 | 仅 `requested_ids`、`superseded_ids`、`missing_ids`、`count`；绝不含正文、Embedding、Hash、Scope、Session、Capsule、Approval、Ledger 或异常正文 |
| 幂等键 | 只能从认证后的 Operation Reference/Capsule 派生；同键同参数重放既有安全投影，不同参数 Fail Closed |

空的规范化 `ids` 是唯一无需审批的安全短路：返回空成功投影，且零 Pending、Approval、Reservation、Invoker
或数据库写入。它不是绕过批准的批量操作。

## 2. 审批、执行与恢复

一次非空请求只能经过下面的固定状态路径；其中任一验证失败均不得调用 Invoker：

```text
Deferred forget_memory Tool
  -> encrypted Capsule + Pending Operation + Approval + Session Anchor
  -> authenticated local operator decides APPROVED
  -> exact Anchor/Capsule verification + one durable Reservation
  -> RUNNING -> narrow MemorySoftForgetPort -> safe Ledger result
  -> conditional Session completion, or COMMIT_UNREPORTED
```

1. 现有受控 Producer 只能创建一个 Pending；它不能批准、调用 `MemorySoftForgetPort`、启动 Worker 或自动 Resume。
   C3 本轮也不创建新的 `POST`/`DELETE` 控制面路由。
2. 审批只能由既有 Loopback 认证边界后的本机 operator 使 Inbox 中的同一 Approval 进入 `APPROVED`。Capability、
   Producer、Recovery Coordinator 和自动化都不得自行制造该决定。
3. 仅当已认证 Capsule 与静态 Tool/Version/Risk/参数、Operation、当前 Anchor 和未过期批准精确匹配，且唯一
   Reservation 已取得时，Recovery 才能写 `RUNNING` 后调用窄口 `MemorySoftForgetPort`。
4. 取消、到期、新 Turn/Anchor 变化、绑定不符、竞争失利或已有终态均为零 Invoker。未能确定 Invoker 结果时写
   `UNKNOWN`；安全结果已写但 Conversation CAS 未报告时写 `COMMIT_UNREPORTED`；二者均绝不自动重放。
5. `SUPERSEDED` 不再进入列表、召回、Context 或候选计数；同 Scope、同内容的显式 UPSERT 才能按既有 V2 规则重激活。
   既有 HTTP 物理 `DELETE` 仍是另一条未被本 Contract 选择的 API 语义。

## 3. 激活与数据权限

运行时仍以 `DISABLED` 为唯一默认值。即使未来显式配置，所有既有条件也必须同时成立：Servlet Runtime、
`agent.memory.mode=JAVA_NATIVE`、Loopback Control Plane、Loopback Approval Inbox、
`agent.capabilities.memory-forget.mode=LOOPBACK_APPROVAL`、健康的版本化 SQLite Store，以及规范 Base64 的
AES-256 Capsule Key。`APPROVAL_REQUIRED` 只额外控制 Deferred Tool Producer 的可见性，不替代审批或恢复检查。

本次选择的授权边界仅覆盖临时 Java SQLite、固定时钟、Fake Approval 和 Fake Invoker 的离线验证。它不授权：

- 用户、Python `memory2.db`、现有生产库或真实 Workspace 的读取或写入；
- 真实网络、Telegram、Provider、MCP、文件、Shell、前端、CLI+Web、Worker、轮询或自动恢复；
- 全局/跨 Scope Memory 查询、编辑、物理删除、`memorize`、Optimizer/Consolidation；
- Session 或 Message 的编辑、删除、批量删除、投递或重试。

因此，现有 `GET /api/v1/control/history/detail` 的只读 `detailRef` 不能成为 Memory 修改凭据；它既不携带
Memory ID，也不授予 C3 写入权限。

## 4. Fixture 与验收归属

本选择不重新发明 R11 的数据语义，而是将 C3 严格绑定到已经版本化的 Java-owned Fixture：

| Fixture | 固定的边界 |
| --- | --- |
| `tools/memory-forget-capability-v1.json` | 静态定义、严格参数、当前 Scope、软失效/再激活、脱敏投影、幂等、`UNKNOWN` 与 `COMMIT_UNREPORTED` |
| `tools/memory-forget-pending-producer-v1.json` | Deferred 可见性、非空调用只创建 Pending、固定 Pending 投影、零 Invoker 与零自动恢复 |
| `tools/pending-operation-v1.json` | Capsule、Approval、Reservation、Ledger、Anchor 与失败优先级 |
| `control-plane/pending-recovery-control-v1.json` | Loopback Resume/Cancel/Status 的认证、稳定响应和零重放 |

后续若要增加任何新的 C3 管理命令、Route、参数、作用对象或数据源，必须先新增独立、版本化 RED Fixture，
再另立 Capability Contract 与用户批准；不得修改本契约或上述 Fixture 来偷偷扩大 `forget_memory`。

## 5. C3 的后续顺序

本 Contract 只完成 C3-C0 的选择与边界冻结，不宣称 C3 或 R13 已完成。后续实现仍按以下顺序推进：

1. 用现有临时 SQLite/Fake 覆盖从批准决定到恢复结果的 C3 纵向回归，确认现有 R11-B2c 边界没有被控制面读取能力削弱；
2. 仅在需要新的管理入口且用户明确批准其请求/目标引用模型后，先写该入口的 RED Fixture；
3. 才能实现最小 Producer/Route，随后覆盖取消、过期、并发、审计失败、`UNKNOWN` 与 `COMMIT_UNREPORTED`；
4. C3 全部实现后，再与 C5 一起执行默认、`failure`、`compat` 三套完整门禁。
