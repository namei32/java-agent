# R13-C2-B 受限会话历史元数据详情设计

- 状态：已批准，实施中
- Contract：[R13-C2-B 受限历史详情决策门禁](../contracts/r13-c2-b-history-decision-gate.md)
- 计划：[R13-C2-B 受限历史详情实施计划](../plans/2026-07-19-r13-c2b-history-detail-implementation-plan.md)

## 1. 单一可观察能力

在已有 Loopback、Bearer、Servlet 和 `Cache-Control: no-store` 条件下，认证 operator 可为其唯一当前 Scope 签发一个
短期 `detailRef`，并只读取得该 Scope 最近 24 小时内 `USER`/`ASSISTANT` 消息的时间和 role。能力不返回任何正文或摘要，
也不接受任何 Session、Route、Sender 或 chat ID。

唯一 Route 是 `GET /api/v1/control/history/detail`：

1. 无 query：Resolver 成功时签发一个 `detailRef`，否则 404；不读取 SQLite。
2. `ref=<opaque>`，可选 `pageSize=1..20`：一次性消费 Ref，读取第一页。
3. `cursor=<opaque>`，可选 `pageSize=1..20`：一次性消费 cursor，返回下一页。

`ref` 与 `cursor` 不能同时提供；unknown、重复 query、body、非 GET 和原始 ID 均为 `400 CONTROL_REQUEST_INVALID`。

## 2. 分层与数据流

```text
Bearer principal -> ControlHistoryScopeResolver -> HistoryScopeCapability
    -> DetailRef store -> ControlHistorySnapshotPort -> read-only adapter
    -> redacted page -> one-time cursor store -> Controller response
```

Kernel 只定义 Scope Capability、Ref/Cursor/Page 值对象和只读 Port；它不依赖 Spring/JDBC。Bootstrap 负责 principal、
Route、Ref/Cursor、HTTP 和审计；SQLite Adapter 只使用固定 SQL 和只读连接。Scope Resolver 是唯一允许将 actor 映射到
当前 Session 的边界，默认实现返回空值。

## 3. 不变量

- Adapter 只能选择 `role`、`ts`、内部排序辅助 `seq`；不得选择 `content`、`id`、`session_key`、`tool_chain` 或 `extra`。
- Scope 校验、schema 校验、role 校验和 24 小时范围必须先于投影；任何失败返回空的稳定失败结果，绝不回退到全库或跨 Scope。
- Ref/Cursor 是 22 字符、内存、60 秒、actor/Scope-bound 的一次性随机值；它们不从 Session/Message/Turn 派生。
- SQLite 连接在固定查询前显式执行 `PRAGMA query_only=ON`，无 DML；测试数据库仅由测试在临时目录创建。
- 单一 Scope 的候选扫描硬上限为 1,024；超过上限返回空的 `DEGRADED/CONTROL_SNAPSHOT_UNAVAILABLE`，绝不产生部分页。
- `toString()`、异常、HTTP、Fixture expected、日志和审计均不出现正文、Session/Scope/actor 原值、SQLite 路径、内部 sequence 或原始 Ref。
- 审计使用现有 `ControlPlaneAudit` 的哈希字段；任何 sink 异常不得改变读取、认证或状态码。

## 4. 失败投影

| 情况 | HTTP/响应 |
| --- | --- |
| 无 Scope、错误 actor、跨 Scope、Ref/Cursor 过期或已消费 | `404 CONTROL_HISTORY_NOT_FOUND`，无 items |
| 畸形或冲突 query、body、非 GET | `400 CONTROL_REQUEST_INVALID` |
| 默认 DISABLED、非 Servlet 或非 Loopback | Route/Bean 不存在，复用既有控制面行为 |
| schema/role/编码/存储失败、关闭或取消 | 脱敏的 `200 DEGRADED CONTROL_SNAPSHOT_UNAVAILABLE` 与空 items |

## 5. 验收

先由 30+ Case compat Fixture 冻结上述形状和秘密排除；然后分别为 Kernel、Fake/temporary SQLite、Servlet wiring 与
Ref/Cursor 竞争写 RED/GREEN。C2-B 完成后只运行受影响模块和 Fixture；R13 的三套完整门禁仍在 C3 或 C5 阶段点执行。
