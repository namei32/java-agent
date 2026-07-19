# R13-C2-B 受限历史详情决策门禁

- 阶段：R13-C2-B0
- 状态：已完成；已冻结决策输入与拒绝默认值，**未授权任何 C2-B 数据读取或运行时实现**
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

## 3. 必须由用户逐项确认的业务值

下表中的 `待确认` 不是默认允许。任何一项未确认时，C2-B1 及后续任务不得开始；实现应继续只保留 C2-A。

| ID | 决策 | 待确认的精确值 | 未确认时的固定行为 |
| --- | --- | --- | --- |
| B0-D1 | 数据范围 | Java `sessions.db` 的哪些 schema version、仅当前 Session 还是 actor 的哪些 Scope | 零数据源访问 |
| B0-D2 | 可见角色与正文 | 允许的 role 枚举；是否返回正文/摘要；单条、单页和总字符预算 | 零正文、零摘要、零消息字段 |
| B0-D3 | Retention | 可见历史最大年龄、到期清理责任、详情 Ref TTL 与过期 HTTP/code | 不签发详情 Ref |
| B0-D4 | actor/Scope 映射 | Operator actor 如何获得内部 Scope Capability；跨 Scope 的统一拒绝语义 | 无映射即拒绝，不泄漏存在性 |
| B0-D5 | 列表与详情形状 | 是否需要列表、详情 Route、允许的固定 sort、page size/max page、cursor TTL | 不新增 Route 或 query |
| B0-D6 | 审计与可观测性 | 允许记录的 hash-only 审计字段、保留期与 sink 失败策略 | 不新增 C2-B 审计事件 |

## 4. 建议的最小 C2-B 候选（尚待确认）

为减少授权面，后续确认时建议选择下列最窄组合：只读取 Java 自有的当前 Scope；只允许显式白名单 role；详情 Ref 与
cursor 均为 60 秒、一次性、actor/Scope-bound 内存值；默认页 10、最大 20；固定时间倒序；每页固定字符总预算；无搜索、
无任意过滤、无下载、无导出。即使采用该候选，正文是否可见仍必须由 B0-D2 单独确认。

## 5. B0 退出与重新进入条件

C2-B0 已完成，因为范围、永久禁令、必要决策、默认拒绝和后续 TDD 任务均已固定。它不意味着 C2-B 已批准或开始。

只有用户以 B0-D1 至 B0-D6 的具体值明确确认后，才能将本文件状态更新为“已批准实现”、新增版本化 Fixture，
并从 C2-B1 开始。任何扩大数据来源、角色、正文、Retention、查询能力或远程访问的请求都必须重新更新本门禁。
