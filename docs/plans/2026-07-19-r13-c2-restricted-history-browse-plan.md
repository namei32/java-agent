# R13-C2 受限历史浏览执行计划

> **执行状态：C2-A 与 C2-B 已实现并完成聚焦验证；其他 C2 变体及 C3 写 Capability 仍需单独授权。**
> **C2-B 只允许当前 Scope、零正文、24 小时、固定分页的只读投影。自动化只使用临时 Java SQLite/Fake；默认生产 Scope/Port 拒绝，禁止网络、真实 Telegram、CLI+Web、前端或任何写操作。**

## 1. 目标与非目标

C2 的候选目标是在既有 Loopback、短期 Bearer Operator Session 与 Request ID 边界内，按服务端生成、短期、
actor-bound 的不透明 `historyRef` 查询**受限的只读历史投影**。

它不是 Python Dashboard 迁移，也不是通用会话浏览器。它不包含：

- 原始 Session/Route/Sender/chat ID 作为路径、query、日志或响应字段；
- 全文检索、跨 Scope 查询、Memory 浏览、Embedding、Provider reasoning、Tool 参数/结果、审批 Capsule 或 Ledger；
- 编辑、删除、重试、恢复、投递、Optimizer、Proactive、前端、真实渠道或远程访问；
- 通过 C1 的活动 `turnRef` 反查任意历史。C1 的 `turnRef` 不能被重新解释为 `historyRef`。

候选 Route 仅供 Contract 讨论，尚未冻结：`GET /api/v1/control/history/{historyRef}`，可选受限分页 cursor。

## 1.1 已批准的 C2-A 基线：内存终态目录

C2-A 的唯一运行时 Route 固定为 `GET /api/v1/control/history`。它只从既有 `ActiveTurnRegistry` 的内存终态
Tombstone 读取数据；Tombstone 已有容量与 TTL，进程关闭后清空。它不读取 `sessions.db`、任何 Memory/Channel
Ledger、Python 数据、用户 Workspace 或真实渠道。

成功响应固定为 `schemaVersion`、`observedAt`、`state`、`code`、有界 `items` 与 `nextCursor`。每个 item 只能为：

```json
{
  "historyRef": "22 位随机不透明引用",
  "channel": "telegram",
  "terminalState": "COMPLETED",
  "completedAt": "2026-07-19T00:00:00Z"
}
```

- `historyRef` 由服务端为当前 actor 生成，固定为 16-byte URL-safe 随机值、短期（1 分钟）、内存有界；它不复用
  `turnRef`、Session ID 或 Cursor，且 C2-A 不接受它作为任何请求参数。
- 目录按 `completedAt` 倒序、内部 `turnRef` 字典序稳定排序；后者只作内部 tie-break，永不投影。
- page size 默认 20、上限 50；cursor 也是短期、一次性、actor-bound 的内存值。
- 目录只能表示 `COMPLETED`、`CANCELLED`、`FAILED` 或 `SOURCE_ENDED` 的终态与安全 channel 名称。它不包含正文、
  role、message count、最后 sequence、错误 code、Session、Route、Sender、Tool/Provider/Memory/Approval 数据。
- `DISABLED` 无 Controller；远程、非 GET、body、未知/重复 query、无效/原始 cursor 与无/过期/撤销 Bearer 均按既有
  稳定控制面错误 Fail Closed。Registry 快照异常只返回 `DEGRADED`/`CONTROL_SNAPSHOT_UNAVAILABLE` 的空目录。

`historyRef` 在 C2-A 只是目录标识，不授予正文、Session 或任何详情读取能力。已完成的 C2-B 另行签发
`detailRef`，绝不复用 C2-A `historyRef`；其全部固定语义见 C2-B 决策门禁。

## 2. 实现前必须由用户明确决定

下列项目不能由实现者推断；它们共同决定 C2 是否可实现及其真实数据边界。

| 决策 | 必须明确的内容 | 默认处理（尚未批准时） |
| --- | --- | --- |
| 数据源 | 只允许 Java `sessions.db`，还是允许其他来源；是否仅当前会话 | 不读取任何来源 |
| 可见正文 | 是否允许 message text；允许哪些 role；是否允许摘要 | 不返回正文、摘要或消息元数据 |
| 永久禁止字段 | Tool 参数/结果、reasoning、Provider 原始字段、Memory、审批数据、附件等 | 全部禁止 |
| 保留期 | 历史最多保留多久；到期后的固定响应 | 不创建历史引用或持久化映射 |
| 数量与分页 | 单页/总量/字符预算、排序、最大 cursor 生命周期 | 不接受页码、搜索或任意过滤条件 |
| 引用签发 | `historyRef` 何时签发、绑定哪个已认证 actor、撤销/过期规则 | 不复用 `turnRef`、Session ID 或 Cursor |
| 空值与错误 | 空历史、过期、撤销、快照失败、存储不可用的稳定码与 HTTP 状态 | Fail Closed，且不泄漏存在性或异常正文 |

建议的最小批准模型是：仅 Java 自有会话、仅安全 role 的显式白名单、无 Tool/Provider/Memory 字段、固定总量和
字符预算、短期 actor-bound `historyRef`、无搜索。任何扩大范围都必须形成新的版本化 Contract。

### 2.1 C2-B0 已完成的决策门禁

[R13-C2-B 受限历史详情决策门禁](../contracts/r13-c2-b-history-decision-gate.md)已固定并实现 B0-D1 至 B0-D6：
31 Case Fixture、Kernel/Port、临时 SQLite/Fake、默认拒绝的 Loopback Service/Controller、一次性消费竞争、关闭和
审计失败闭环均已完成。详细证据与阶段暂停点见
[R13-C2-B 受限历史详情实施计划](2026-07-19-r13-c2b-history-detail-implementation-plan.md)。

## 3. 连续 TDD 顺序

### C2-C0：数据边界 Contract 与 Fixture（C2-A 已完成）

1. 记录第 2 节的明确决策、威胁模型和保留/撤销策略。
2. 新增版本化 Java-owned Fixture；先固定激活、认证、Ref、投影、分页、过期、撤销、失败和敏感字段拒绝。
3. 以 RED Fixture 验证：原始 Session/chat ID、正文 query、Tool/Provider/Memory 字段、跨 Scope 与任意 search 都不可接受。
4. C2-A 只允许其内存终态目录 Route；不创建详情 Route、SQLite 查询或生产配置。

### C2-B：已完成的安全详情（B1–B6）

1. 31 Case Java-owned Fixture 冻结严格 GET/query、actor/Scope、角色/字段、24 小时/1,024 candidate/20 item 预算、
   一次性 Ref/Cursor 和稳定失败；expected 不含任何秘密。
2. Kernel 的 Capability、Ref/Cursor、Page/Request 和只读 Port 不接受原始 Session/Route/Sender、SQL 或全文 query。
   SQLite Adapter 仅以临时 Java 数据库验证固定列、`query_only`、排序、分页和 fail-closed 行为。
3. 唯一 `GET /api/v1/control/history/detail` 只在显式 Loopback + Servlet 出现；生产默认 Scope Resolver 与 Snapshot Port
   都拒绝。并发、过期、撤销、关闭和审计 sink 失败均已验证；它不创建 Pending Operation、Approval、Capsule、Ledger、
   Reservation 或恢复 Worker。

> 完整输入、RED/GREEN 证据、完成条件与阶段暂停点见
> [C2-B 详情实施计划](2026-07-19-r13-c2b-history-detail-implementation-plan.md)。

### C2-B6：已完成的文档、聚焦门禁与提交准备

1. C2-B 已同步 Contract、Fixture、Spec、ADR、R13 计划、README、Roadmap 与能力矩阵。
2. C2-B 运行受影响模块的 default/failure/compat 聚焦测试与格式检查；R13 阶段三套全门禁仍按 C3/C5 决策执行。
3. 不推送、不创建 PR，除非另获授权。

## 3.1 C2-A 实现证据

- `ActiveTurnRegistry` 只产生有界、TTL 的终态安全快照：内部 `turnRef`、channel、终态、完成时间；其公开
  `toString()` 始终脱敏引用。
- `ControlHistoryReferenceStore` 与 `ControlHistoryCursorStore` 都是 16-byte、1 分钟、128 条上限的内存映射；前者
  actor-bound，后者同时 actor-bound 且一次性。C2-A 不将 reference 用作任何 HTTP 入参。
- `ControlPlaneStatusService.history` 与 `GET /api/v1/control/history` 只投影
  `historyRef/channel/terminalState/completedAt`。快照失败返回空的 `DEGRADED/CONTROL_SNAPSHOT_UNAVAILABLE`；关闭返回空的
  `SHUTTING_DOWN/CONTROL_SHUTTING_DOWN`。
- 已有 RED/GREEN 证据覆盖 Registry 快照、reference、cursor、服务分页、Controller/Loopback Guard 和 22 Case
  `compat` Fixture。未运行 R13 阶段 `clean verify`、`-Pfailure verify`、完整 `-Pcompat verify`，也未启用任何网络、
  真实 Telegram、CLI+Web、前端、SQLite 历史读取或写操作。

## 4. Fixture 验收类别

实际 Case 数由 C2-C0 决定，但至少覆盖以下类别：

| 类别 | 必须证明的行为 |
| --- | --- |
| 激活 | `DISABLED` 无 Route；LOOPBACK/Servlet 才可映射；远程绑定拒绝 |
| 认证 | 缺失、过期、撤销 Bearer 统一拒绝；有效 Bearer 不投影 actor |
| 引用 | 仅服务端签发的 actor-bound `historyRef` 可用；原始 Session/Turn/Route 不可替代 |
| 数据边界 | 仅获批来源、Scope、role、字段与保留期；禁止字段永不出现 |
| 分页 | 稳定排序、固定上限、短期 cursor、一次性/并发消费与过期 |
| 失败 | 空历史、Ref 不存在/撤销、存储异常、关闭和损坏数据均为稳定脱敏投影 |
| 不变量 | 零 DML、零网络、零真实渠道、零 Tool/Provider/Memory/Approval 数据泄漏 |

## 5. 退出条件与后续

C2 只有在所有明确数据决策已批准、Fixture 与实现逐项有 RED/GREEN 证据、文档同步且聚焦门禁通过后才可标记完成。
这仍不解冻 C3 写入、C4 前端、C5 真实渠道或 R13 阶段的全门禁。

C2 之后的顺序保持：C3 逐 Capability 的审批写入 → C4 前端供应链（单独解除冻结后） → C5 渠道切片；在 C3/C5
完成后统一运行 `clean verify`、`-Pfailure verify`、`-Pcompat verify`。
