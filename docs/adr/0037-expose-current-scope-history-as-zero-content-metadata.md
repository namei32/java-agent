# ADR-0037：将当前 Scope 历史详情限制为零正文元数据

- 状态：已接受
- 日期：2026-07-19
- 决策人：用户，确认 R13-C2-B 最小范围
- 关联：[R13-C2-B 受限历史详情决策门禁](../contracts/r13-c2-b-history-decision-gate.md)

## 背景

R13-C2-A 只能列举进程内终态元数据，且其 `historyRef` 不能成为持久化 Session 或正文读取权限。控制面仍需要一个
比 C2-A 更窄、可审计的“当前 operator 当前 Scope 的近期会话活动”只读能力；直接复用 Python Dashboard 或暴露
Session ID、正文、搜索和任意历史会破坏现有数据和身份边界。

## 决策

1. 仅在既有 `LOOPBACK`、Servlet、Bearer 和默认 `DISABLED` 门禁内增加
   `GET /api/v1/control/history/detail`。无 query 只签发一次性 `detailRef`；`ref` 读取第一页；`cursor` 读取后续页。
2. 认证 principal 必须经内部 `ControlHistoryScopeResolver` 转为预绑定单一 Session 的不透明 Scope Capability。默认
   Resolver 拒绝全部；缺失、错误、跨 Scope、过期与已消费统一返回 `404 CONTROL_HISTORY_NOT_FOUND`。
3. 读取仅允许 Java 自有的当前 `SqliteSchemaInitializer` Session schema，并只选择最近 24 小时 `USER`、`ASSISTANT`
   的 `role`、`ts`。响应不含正文、摘要、字数、ID、sequence、Session、actor、Scope、Tool、Provider 或异常文本。
4. Ref/Cursor 均为 22 字符随机、内存、60 秒、actor/Scope-bound、一次性值；页大小固定默认 10、最大 20，时间倒序，
   不支持搜索、过滤、下载、导出或字段选择。
5. Adapter 使用固定只读 SQL 和临时 Java SQLite/Fake 验收；无 DML、Worker、SSE、网络、真实渠道、前端或 CLI+Web。
   审计只复用既有 hash-safe `ControlPlaneAudit`，sink 失败不得影响结果。

## 后果

这提供了零正文的当前 Scope 运维可见性，而不形成通用 Session Browser 或数据导出能力。任何正文、其他角色、长保留期、
新数据源、远程访问、可搜索性或 Scope 映射配置扩展，都必须建立新的 Contract 与批准，不能通过修改默认值隐式获得。
