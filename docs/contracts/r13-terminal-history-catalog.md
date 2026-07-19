# R13-C2-A 内存终态历史目录 Contract

- 阶段：R13-C2-A
- 状态：已实现并完成聚焦验证；仅本机、内存、metadata-only 目录
- Fixture：`testdata/golden/control-plane/r13-terminal-history-catalog-v1.json`
- 前置：[R13 C2 实施计划](../plans/2026-07-19-r13-c2-restricted-history-browse-plan.md)、[Loopback 控制面 Contract](loopback-control-plane.md)

## 1. 激活与认证

唯一 Route 是 `GET /api/v1/control/history`。它只在既有 `agent.control-plane.mode=LOOPBACK`、Servlet、
Loopback Guard、短期 Bearer Operator Session 和 Request ID 同时有效时映射。默认 `DISABLED` 不创建 Controller。
远程绑定、远程请求、非 GET、body、缺少/过期/撤销 Bearer 继续使用既有稳定控制面错误。

## 2. 数据来源与投影

目录只读取 `ActiveTurnRegistry` 的未过期终态 Tombstone；该数据仅存在内存，受既有 `terminalRetention` 与
`maxTerminalTombstones` 限制，Runtime 关闭即清空。它不得读取 `sessions.db`、Memory、Channel Ledger、Workspace、
Python 文件、真实渠道或网络。

成功响应只能包含 schema version、观察时间、状态、稳定 code、有界 items 与 cursor。item 只能包含：

- 服务端随机生成的 22 字符 `historyRef`；
- 既有公开 channel 名称；
- `COMPLETED`、`CANCELLED`、`FAILED` 或 `SOURCE_ENDED`；
- 完成时间。

不得读取、接受、返回、哈希或记录内部 `turnRef`、Session、Route、Sender、正文、role、message count、sequence、
terminal code、Tool/Provider/Memory/Approval 数据、SQLite 路径、token 或 actor 身份。

## 3. 排序、引用、分页与失败

items 按 `completedAt` 倒序、内部 `turnRef` 字典序稳定排序。`historyRef` 由服务端为当前 actor 生成；它是内存、
1 分钟、容量受限且 actor-bound 的不透明引用，不能由 `turnRef`、Session 或 Cursor 派生，也不能在 C2-A 被用作
详情查询。

page size 默认 20、硬上限 50。cursor 是服务端生成、内存、1 分钟、一次性、actor-bound 的不透明值。超限、畸形、
原始 Session/Turn/Message 值、未知/重复 query、body 与非 GET 均为 `CONTROL_REQUEST_INVALID`。Registry 快照异常仅可
返回 `200`、`DEGRADED`、`CONTROL_SNAPSHOT_UNAVAILABLE` 与空 items；不得泄漏异常文本或部分内部数据。

## 4. 非目标

本 Contract 不提供消息详情、历史正文、搜索、Scope 过滤、详情 Route、SQLite 查询、持久化引用、写操作、审批、
Capsule、Ledger、恢复、SSE、前端、CLI+Web、真实 Telegram 或远程访问。
