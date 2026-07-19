# R13-C0 Loopback 只读控制索引 Contract

- 阶段：R13-C0
- 状态：C0 Contract/Fixture 已冻结；其 C1 活跃 Turn/渠道健康投影已完成，历史表面仍未实现
- Fixture：`testdata/golden/control-plane/r13-read-only-control-index-v1.json`
- 复用 ADR：[ADR-0011](../adr/0011-use-authenticated-sse-for-loopback-control-events.md)
- 前置：[Loopback 控制面 Contract](loopback-control-plane.md)、[R13 对齐计划](../plans/2026-07-19-r13-dashboard-channel-alignment-plan.md)

## 1. 范围

R13-C0 冻结了 Loopback 只读索引的消息形状，供 C1 实现有界渠道健康与活跃 Turn 投影。C1 已新增受既有条件保护的
`GET /api/v1/control/index`；C0 本身没有引入 Controller、Route、Bean、数据库查询、前端、SSE、历史读取或写入。
这不授权新的历史表面或对现有 `/api/v1/control/*` 的其他扩展。

候选将来 Route 固定为 `GET /api/v1/control/index`。它仅在既有 `agent.control-plane.mode=LOOPBACK`、Loopback Guard、
短期 Bearer Operator Session 和 Request ID 全部有效时才可映射。`DISABLED`、远程绑定、任何写方法、缺少/过期/撤销
Bearer 仍按既有稳定控制面错误拒绝。

## 2. 最小投影

成功投影只能包含：

- 固定 schema version、观察时间和有界的 `channels`；
- 每个渠道的固定公开名称、状态与安全计数；
- 每个活跃 Turn 的既有不透明 `turnRef`、渠道、状态、最后 sequence 与稳定排序；
- 不透明、短生命周期的分页 cursor，或明确的空 cursor。

它不得读取、接受、返回、哈希或日志记录原始 Channel Session、Route、Sender、聊天/Tool/Memory 正文、消息 ID、
Provider 字段、Embedding、审批参数、SQLite 路径、Operator token 或 actor 身份。终态 Turn 不在索引中；未知执行只能
作为计数出现，不能伪造 Turn。

## 3. 分页、排序与失败

默认 page size 为 20、硬上限为 50；排序必须为渠道名称、再 `turnRef` 的稳定字典序。cursor 必须是服务端生成的
不透明值，不能接受原始 Session/Turn/Message 标识或任意 search/filter query。空快照返回 200 与空数组；超限、畸形
cursor、未知 query、非 GET 与未认证请求失败关闭，不退化为宽松查询。

底层状态快照失败只允许安全 `DEGRADED`/`CONTROL_SNAPSHOT_UNAVAILABLE` 投影；不能泄漏异常文本或部分内部数据。C0
不规定 Session/Message 历史、Memory 浏览、全文检索、编辑、删除、Optimizer、Proactive 或 Channel 投递的 API。

## 4. 验收与后续

20 个 Fixture Case 固定激活、认证、公开字段、排序、分页、输入拒绝与敏感字段禁令。C0 的 `compat` consumer 继续验证
Contract Fixture；C1 的聚焦测试补充了 Loopback Filter、默认关闭、容量、游标和失败投影证据。C1 不是历史浏览；只有在
C2 的数据保留、正文脱敏和不透明历史引用 Contract 获明确批准后，才可能讨论受限历史浏览。
