# R11 B2a Approval Inbox 设计

- 状态：已实现并验证（B2a）
- 日期：2026-07-18
- 对应契约：[本地审批收件箱与待处理操作安全契约](../contracts/tool-approval-inbox.md)

## 组件

```text
Tool Capability（未来） -> ApprovalInbox Port -> JdbcApprovalInbox (approval-inbox.db)
                                 ^                         |
                                 |                         v
                  Loopback Controller <--- Operator Session / CAS state
```

`ToolApprovalGate` 不接入该图；它保留当前同步 Framework 行为。B2a 使用 Test Fixture 或未来 Capability 发起请求，验证 Inbox 自身，而不伪造一个能恢复真实调用的半成品。

## Application 模型

- `ApprovalInboxEntry`：持有受限 `ApprovalRequest`、`approvalRef`、状态、决定时间和仅内部 Actor Reference。
- `ApprovalInbox`：`create`、`list`、`resolve`、`expireDue`；所有 Mutation 返回权威 Entry 或稳定结果，不允许调用者提供 Fingerprint、时间或 Actor。
- `ApprovalInboxResolution`：只接受 `APPROVED|DENIED`，由接口验证；`EXPIRED|CANCELLED` 是 Runtime 路径。
- `ApprovalInboxReferenceGenerator`：加密随机 128 bit+ Base64URL，和 Control `turnRef`、Token 相互独立。

## SQLite 原则

Schema 使用单例版本表和 `approval_inbox_entries`。唯一约束覆盖 `approval_id`、`approval_ref`；状态转移通过带旧状态和过期谓词的单条更新实现。每次读写均在事务内惰性过期；决定 UPDATE 的影响行数为零时必须重新读取有限公开状态并映射为终态冲突，不能猜测成功。

数据库只存允许给 Operator 看的 Summary 和秘密绑定摘要；绝不存 Arguments JSON 或模型上下文。失败时抛出专属不可用异常，由 API 映射稳定码；不回退内存。

## HTTP 边界

仅在两个模式同时为 LOOPBACK 时创建 Controller。Security Filter 完成认证后，Controller 从 request Attribute 取 `actorRef`，不接受客户端 Actor。输入 Body 通过严格 DTO 解析，响应 DTO 不持有内部 `ApprovalRequest`。

## 后续切片

1. B2a：Inbox Schema/Port/Adapter/API/Fixture，不接执行。
2. B2b：Pending Operation Contract 与加密/隔离执行胶囊，明确请求结束和恢复入口。
3. B2c：Durable Side Effect Ledger Adapter，与一次性消费/恢复/UNKNOWN 验证。
4. B3+：每个真实 Tool 的 Capability Contract、沙箱和独立授权。
