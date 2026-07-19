# R13-C2-B 受限历史详情决策门禁

- 阶段：R13-C2-B（B0 决策门禁）
- 状态：B0 至 B6 已完成；当前实现仍保持默认拒绝的数据接线，后续 C3 写 Capability 必须另获批准
- 前置：[R13-C2 受限历史浏览执行计划](../plans/2026-07-19-r13-c2-restricted-history-browse-plan.md)、[R13-C2-B 详情实施计划](../plans/2026-07-19-r13-c2b-history-detail-implementation-plan.md)、[R13-C2-A 内存终态历史目录 Contract](r13-terminal-history-catalog.md)

## 1. C2-B0 的完成范围

C2-B0 的交付物是一个可审计的决策门禁，而不是历史 API、SQLite 查询、Fixture 或消息投影。它确认 C2-A 的
`GET /api/v1/control/history` 仍只列举内存终态元数据；C2-B 不得复用 C2-A 的 Tombstone、`historyRef` 或 cursor
作为持久化历史、Session 或正文的授权凭据。

本阶段未读取 `sessions.db`、用户工作区、Python 数据、Memory、Ledger 或任何真实渠道；未创建详情 Route、Schema、
Port、Adapter、DML、网络调用或测试数据库。

## 2. 已冻结的安全前提

以下前提已获准，并且是后续每项 C2-B 工作的不可绕过条件：

1. 数据候选只可来自 Java 自有、版本受控的 Session 存储；Python 数据、用户工作区、Memory、Channel Ledger、
   Tool/Provider/Approval 数据和真实渠道永远不在 C2-B 范围内。
2. 任何详情读取都必须先把认证 actor 映射为内部 Scope Capability。请求、日志、cursor、response 和 SQL 参数都不得
   接受或返回原始 Session/Route/Sender/chat ID；无法证明 Scope 时必须 Fail Closed。
3. Tool 参数/结果、reasoning、Provider 原始 payload、Memory、Approval/Capsule/Ledger、附件、token、actor、
   SQLite 路径、异常文本和内部 `turnRef` 是永久禁止字段。
4. C2-B 是只读能力：零 DML、零 Pending Operation、零审批决策、零恢复 Worker、零投递、零网络、零真实 Telegram。
5. C2-A `historyRef` 只能作为目录标识，绝不自动转化为详情 Ref。若 C2-B 需要详情 Ref，必须新签发、短期、
   actor-bound、Scope-bound 且可撤销的值。

## 3. 已确认的业务值

用户于 2026-07-19 确认以下最小范围。下表是 C2-B 的唯一有效授权；其外的值仍为拒绝默认值。

| ID | 决策 | 已确认的精确值 | 固定拒绝行为 |
| --- | --- | --- | --- |
| B0-D1 | 数据范围 | 只读 Java 自有的 `SqliteSchemaInitializer` 当前 `sessions`/`messages` schema；每次只允许由服务端 `HistoryScopeCapability` 绑定的一条当前 Session。自动化只能使用临时 Java SQLite/Fake，绝不打开用户、Python 或生产数据库。 | 任意原始 Session ID、未知 schema/列或非当前 Scope 均不读取。 |
| B0-D2 | 可见角色与正文 | 只允许 `USER`、`ASSISTANT` 的元数据项 `role`、`occurredAt`；不返回正文、摘要、字数、message ID、sequence、Tool/Provider 字段。正文总预算固定为零。 | `SYSTEM`、`TOOL`、未知 role、NULL/损坏字段全部 Fail Closed，不作跳过或宽松投影。 |
| B0-D3 | Retention | 只读取 `ts` 在请求时刻前 24 小时内的记录；不删除、不清理源数据。详情 Ref 与 cursor 均为内存、actor/Scope-bound、一次性、60 秒。过期、撤销或不匹配统一为 `404 CONTROL_HISTORY_NOT_FOUND`。 | 不签发持久化 Ref；不得以 404 区分不存在、过期或跨 Scope。 |
| B0-D4 | actor/Scope 映射 | 认证后的 Loopback `OperatorSessionPrincipal` 只能交给内部 `ControlHistoryScopeResolver`；Resolver 只返回预绑定单一 Session 的不透明 Capability。默认 Resolver 拒绝全部。 | 缺失、撤销、错误 actor、跨 Scope 或无法映射统一返回相同的 `404 CONTROL_HISTORY_NOT_FOUND`。 |
| B0-D5 | 列表与详情形状 | 唯一新 Route 为 `GET /api/v1/control/history/detail`：无 query 时仅签发 `detailRef`；`ref` 首次消费读取第一页；`cursor` 消费后续页。时间倒序、内部 sequence 仅作稳定 tie-break；默认 10、最大 20；无搜索、过滤、下载、导出或任意字段选择。 | 非 GET、body、未知/重复 query、`ref` 与 `cursor` 并用或原始 ID 一律 `400 CONTROL_REQUEST_INVALID`。 |
| B0-D6 | 审计与可观测性 | 复用现有非持久化 `ControlPlaneAudit`：只记录 action、result、stable code、request ID、actor hash、detail-Ref hash 和条数；不记录 Session/Scope/正文。sink 失败继续忽略，绝不改变 HTTP 结果。 | 不创建新日志、数据库表、指标标签或可反查的审计字段。 |

## 4. 具体响应与分页约束

签发响应只包含 `schemaVersion`、`observedAt`、`state`、`code` 和 22 字符 `detailRef`。读取响应只包含相同的
公共字段、最多 20 个 `{role, occurredAt}` item 和 22 字符 `nextCursor`；消费后的 `detailRef` 不再回送。首次读取
与后续 cursor 均按 `occurredAt` 倒序、内部 sequence 倒序稳定排序。任何项目都不得出现 Session、actor、Scope、
content、message ID、sequence、原始 Ref 或异常文本。

## 5. B0 退出与重新进入条件

C2-B0 已完成且固定值已由 C2-B1 至 B6 实现和聚焦验证。生产装配仍默认提供拒绝全部的 Scope Resolver 与 fail-closed
Snapshot Port；因此即使显式启用 Loopback，也不会自动读取持久化数据。任何扩大数据来源、角色、正文、Retention、查询能力、
Route 或远程访问的请求都必须先重新更新本门禁并取得明确批准。
