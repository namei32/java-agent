# Pending Operation Loopback Control 契约

- 状态：已冻结；R11-B2c 将实现仅限获批 `forget_memory` 的本机映射，默认仍无路由、无 Worker
- 契约版本：1
- 日期：2026-07-19
- 阶段：R11 B2b / O6 A6
- 前置：[Pending Operation Session Anchor 与 Recovery Capability 契约](pending-operation-recovery-capability.md)
- 复用：[Loopback 控制面、安全状态、事件流与活动 Turn 取消契约](loopback-control-plane.md)

> 本文只固定未来、本机、认证后的 Pending Operation 控制消息。当前所有部署继续没有 Pending Recovery 路由：
> 即使 `agent.control-plane.mode=LOOPBACK`，也不会创建 Resume、Cancel、Status 映射、后台 Worker 或 Tool
> Capability。

## 1. 固定的未来表面

当且仅当某个逐 Tool Capability 已单独批准、通过 Sandbox 验收并显式启用时，才可实现下列表面：

```text
GET  /api/v1/control/pending-operations/{operationRef}
POST /api/v1/control/pending-operations/{operationRef}/resume
POST /api/v1/control/pending-operations/{operationRef}/cancel
```

它们复用既有 R6.5 的 Loopback 地址、Host、Origin、Bearer Token、`Cache-Control: no-store`、`requestId`
和审计边界；不创建第二种 Token、Cookie、远程认证、RBAC、SSE、CLI 或 Telegram 入口。

`operationRef` 必须是严格的 128-bit Base64URL 无 Padding 不透明引用。请求不接受 Body、Query 或调用者提供的
Tool Name、Arguments、Result、Session、Cursor、Risk、Boundary、Actor 或 Reason。未知字段、大小写变化、非空
Body、任意 Query、非不透明引用均在查库或竞争 Reservation 前以
`PENDING_RECOVERY_REQUEST_INVALID` 拒绝。

## 2. 动作语义

`STATUS` 只能投影 `schemaVersion`、稳定 `state` 与 `updatedAt`；不返回 Approval、Capsule、Ledger safe Result、
错误正文、Tool、Session 或 Conversation。`UNKNOWN` 与 `COMMIT_UNREPORTED` 只能作为稳定终态显示。

`RESUME` 只能在精确 Anchor、已批准 Operation、唯一 Reservation、明确 Capability 和所有 Sandbox 前置条件同时
成立时被接受。它不接受“重新执行”参数；`PENDING_APPROVAL`、`CANCELLED`、`UNKNOWN`、`COMMIT_UNREPORTED`、
未知引用或不匹配绑定都必须零 Invoker 调用。`UNKNOWN` 永不自动重试。

`CANCEL` 只可在副作用尚未进入 `RUNNING` 前将 Pending/Approved Operation 与 Anchor 固化为取消终态；对已进入
`CONSUMING` 的记录不能充当 kill 或重试信号。终态取消幂等，并始终零 Invoker 调用。

`PENDING_RECOVERY_*` 稳定码和 HTTP 映射固定如下：请求形状无效为 `400`
`PENDING_RECOVERY_REQUEST_INVALID`；缺失引用为 `404` `PENDING_RECOVERY_NOT_FOUND`；未批准、取消、过期、
`COMMIT_UNREPORTED` 或其他不可恢复状态为 `409` `PENDING_RECOVERY_NOT_RESUMABLE`；`UNKNOWN` 为 `409`
`PENDING_RECOVERY_UNKNOWN_REQUIRES_OPERATOR`；运行中取消为 `409` `PENDING_RECOVERY_NOT_CANCELLABLE`；
Store/Session 不可用为 `503` `PENDING_RECOVERY_UNAVAILABLE` 且仅该码可重试。成功 Status、Resume、Cancel
均为 `200`，只返回 `schemaVersion`、`state`、`updatedAt`。这些路由只在获批的 `forget_memory` Capability
显式启用时映射，并复用既有审计边界；不创建 Worker、自动 Resume 或新认证能力。

取消仍不伪装为跨库事务：先在 `approval-inbox.db` 同一事务撤销尚未 `CONSUMING` 的 Approval/Operation，随后
在 `sessions.db` 条件终态化匹配 Pending Anchor。第二步失败也不能恢复第一步或产生执行权；之后 Resume 必须为零
调用。终态取消返回当前稳定状态且不改变任何 Ledger 或 Result。

## 3. 验收与非目标

Java-owned `control-plane/pending-recovery-control-v1.json` 固定 24 个 Case：默认不映射、既有 Loopback 单独
开启也不映射、严格请求形状、Resume/Cancel 的零执行条件、`UNKNOWN`/`COMMIT_UNREPORTED` 停机及 Status 脱敏。
兼容性测试只校验 Fixture 的版本、分组、动作集合和敏感字段禁令，不能替代 HTTP 集成测试。

本文不授权真实或测试外的恢复执行、网络访问、Telegram、MCP、CLI+Web、前端、文件、Shell、消息写入、自动
Worker、生产密钥或 `agent.control-plane.mode` 的新行为。实现这些内容前必须有新的用户批准和逐 Tool Capability
Contract。
