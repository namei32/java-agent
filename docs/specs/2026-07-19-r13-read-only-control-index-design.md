# R13-C1 Loopback 只读控制索引设计

- 状态：C0 Contract/Fixture 与 C1 本机实现已完成；R13 的 C2–C5 仍未开始。
- 关联 Contract：[R13-C0 只读控制索引](../contracts/r13-read-only-control-index.md)
- 前置：既有 Loopback Guard、短期 Operator Session、`ActiveTurnRegistry` 与渠道健康快照。

## 固定范围

- 仅在既有 `agent.control-plane.mode=LOOPBACK` 与 Servlet 条件同时满足时映射 `GET /api/v1/control/index`；默认 `DISABLED` 不创建路由。
- 复用既有 Loopback 地址/Host/Origin 守卫、Bearer Operator Session、请求 ID、`Cache-Control: no-store` 和稳定错误信封；响应不投影 operator 身份。
- 查询仅允许可选的 `pageSize`（1..50，默认 20）与不透明 `cursor`。任何其他参数、重复参数、空值、原始 Session/Route/Message 值或格式无效的 cursor 都返回 `CONTROL_REQUEST_INVALID`。
- 成功响应固定为 `schemaVersion`、`observedAt`、`state`、`code`、按 channel 字典序的 `channels`、按 `turnRef` 字典序分页的 `turns` 与 `nextCursor`（无下一页时为空字符串）。
- channel 仅为 `channel`、`state`、`activeTurns`、`unknownExecutionCount`；turn 仅为 `turnRef`、`channel`、`state`、`lastSequence`。终态 Turn 不进入 Registry 快照，未知执行仅是 channel 计数。
- 游标为进程内、短期（1 分钟）、一次性、绑定已认证 actor 的 16-byte URL-safe 随机引用；其服务器端条目只保留已脱敏的剩余 Turn 投影。游标不落库、不写业务状态、不暴露或派生原始标识。容量固定为 128，满时淘汰最旧条目。
- 任一索引快照读取异常都返回 `200`、`state=DEGRADED`、`code=CONTROL_SNAPSHOT_UNAVAILABLE` 与空数据；不得透传异常文本。

## 明确不做

- 不启用远程访问、真实 Telegram、CLI+Web、前端或历史/正文查询。
- C1 不新增读取、返回、哈希或记录原始 Session、Route、Sender、Provider、聊天/工具/记忆正文、嵌入、审批参数、数据库路径、token 或 actor 身份；既有认证 Filter 的内部 Session 校验与审计边界不改变。
- 不新增写 API、审批决策、恢复动作、持久化或生产数据迁移。
