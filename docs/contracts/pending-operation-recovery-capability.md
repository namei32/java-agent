# Pending Operation Session Anchor 与 Recovery Capability 契约

- 状态：已冻结；A1–A7 的 Fixture、Anchor Model、初始 SQLite 原子写入、安全 Result 条件提交、测试专用 Fake Capability 演练与 Loopback Message Contract 已完成。首个获批 Capability 的默认关闭生产恢复编排、严格本机控制路由和局部 SQLite 验证已由 R11-B2c 实现；其受控 Tool/Chat Producer 只创建 Pending，仍没有自动 Resume
- 契约版本：1
- 日期：2026-07-19
- 阶段：R11 B2b / O6
- 前置：[待审批 Tool Operation、参数胶囊与恢复安全契约](pending-tool-operation.md)
- 关联 ADR：[ADR-0019：在恢复前冻结 Pending Operation 的 Session Anchor](../adr/0019-freeze-pending-operation-session-anchor-before-resume.md)

> 本契约只冻结恢复所需的 Session Anchor 与测试边界。它不启用 Resume HTTP、Cancel HTTP、后台 Worker、
>真实 Tool、网络、文件、Shell、消息发送或任何新的 Spring Bean。唯一例外是已获得单独用户批准、仍默认关闭的
> [Scope 受限 Memory Forget Capability](approved-scope-bound-memory-forget.md)；它的实现必须继续满足本文所有
> Anchor、Reservation、`UNKNOWN` 与零重放要求。

## 1. 不可跨越的前置条件

`RESERVED`、`RUNNING`、`SUCCEEDED` 或 `COMMIT_UNREPORTED` 都不是执行授权。只有一个逐 Tool、显式启用且
经过 Sandbox 验收的 Capability，才能在满足以下全部条件时调用 Invoker：

1. 已从加密 Capsule 重新认证精确 Tool/Version/Risk/Arguments/Fingerprint/Boundary；
2. 读取到版本化 Session Anchor，且该 Anchor 的 Operation Ref、Session、初始 Revision 和预期恢复 Revision
   都一致；
3. 新 Turn、取消、到期、终态 Operation 或既有 Ledger 不使 Anchor 失效；
4. 在同一 `approval-inbox.db` 立即事务获得唯一 `RESERVED`，并先写 `RUNNING`；
5. 在副作用成功后先写安全 `SUCCEEDED` Result，再以 Anchor 的条件追加语义提交 Conversation；
6. Conversation 条件追加失败时只写 `COMMIT_UNREPORTED`，不重放 Invoker。

没有 Capability 的注册、Key、Anchor、Session 条件 Port 或认证后的本机操作入口时，调用次数必须为零。

## 2. Session Anchor 模型

Anchor 是 `sessions.db` 中的版本化内部记录，不等于公开 Message、Approval 或 Capsule。至少绑定：

| 字段 | 规则 |
| --- | --- |
| `anchorVersion` | 固定版本，未知版本 Fail Closed。 |
| `operationRef` | 与 Pending Operation 相同的不透明 128-bit Ref；不在公开 JSON/日志回显。 |
| `sessionId` | 仅存于 Session Store；不得复制到 Inbox/Capsule 明文。 |
| `createdNextSequence` | 创建安全 Pending 投影前的 Cursor。 |
| `resumeNextSequence` | 只允许在此 Cursor 未变化时追加恢复结果。 |
| `state` | `PENDING_APPROVAL`、`CANCELLED`、`STALE_SESSION`、`COMMITTED` 或稳定终态；不可重开。 |
| `projectionVersion` | 决定安全 Pending/完成投影模板；不含 Tool 参数或 Result 原文。 |

Session Anchor 不保存原始 User Message、模型 Prompt、Tool Arguments、Approval/Fingerprint/幂等键、Secret、路径或
异常。原始 Conversation 仍由现有 Message 表保存；Anchor 只承担顺序与恢复关联。

`resumeNextSequence` 之后还必须保留一个可用序号供安全 Result 投影提交；不能形成完整恢复路径的 Cursor 在
Anchor 构造时即拒绝。

## 3. 条件提交与优先级

初始 Turn 必须通过一个新的 SQLite 原子 Port 写入“完整原始 Turn + 安全 Pending 投影 + Anchor”，并把
`resumeNextSequence` 固定为写入后的 Cursor。现有 `appendTurnIfNextSequence` 不能代替它。

恢复成功的 Conversation 更新必须由另一新 Port 在 `resumeNextSequence` 上 CAS，只追加版本化安全 Result 投影；
它不能编辑、删除或补写 User Message。任意状态不匹配返回 `false`，不留下半个 Message 或 Anchor。

优先级固定如下：

1. 新 Turn/已变 Cursor 优先，Anchor 与 Operation 变为 `STALE_SESSION`，Invoker 零调用；
2. 取消或到期在 `RUNNING` 前优先，Invoker 零调用；
3. `UNKNOWN` 不追加 Conversation，不自动重试；
4. `SUCCEEDED` 但 CAS 失败为 `COMMIT_UNREPORTED`，保留 Ledger 安全 Result，Invoker 零重放；
5. 进程重启不自动争用 Anchor 或调用 Invoker。

## 4. API 与 Capability 边界

未来 API 只能是认证后的 Loopback 操作，且单次请求只接受不透明 `operationRef` 和固定 action；不接受 Tool 名称、
Arguments、Result、Session、Cursor、风险、边界版本或模型文本。`resume`、`cancel`、`status` 必须各自有版本化
Request/Response Fixture、速率限制、审计和稳定错误投影。

每个 Capability 必须声明精确 Tool Name/Version、风险、Sandbox 边界版本、最小权限、参数 Schema、审批摘要、
幂等派生、可保存的安全 Result、`UNKNOWN` 触发条件、回退与本地 Fake Invoker 演练。Capability 不能由模型、
Config 字符串、Plugin 或 MCP 返回值动态创建。

## 5. 验收顺序

1. Java-owned `pending-operation-v1` 已扩展 Anchor 版本、Opaque Ref、精确 Cursor、取消/新 Turn 终态、
   `toString` 零泄漏、初始 SQLite 原子创建、旧 Cursor 与插入失败回滚，以及安全 Result 精确 Cursor 提交、
   新 Turn `STALE_SESSION`、取消、提交失败回滚和投影版本拒绝 Case；后续补 `UNKNOWN` 与
   `COMMIT_UNREPORTED` 的编排 Case；
2. `sessions.db` 已实现 Anchor Schema、版本检查、读写、初始 Pending Turn 条件 Port 和安全 Assistant
   Result 的条件提交 Port；仍未实现连接 Operation Ledger 的恢复编排；
3. 已以只在测试源码显式构造的 Fake Capability/Invoker 验证精确 Anchor/Operation 绑定、唯一 Reservation、
   `RUNNING`/`SUCCEEDED`、`UNKNOWN`、`COMMIT_UNREPORTED` 和零重放；它没有生产类型、Bean 或路由；
4. 已冻结认证 Loopback Resume/Cancel/Status Message Contract；默认与仅 Loopback Control Mode 时均不映射路由；
5. 无执行安全基础已通过完整 `clean verify`、`-Pfailure verify`、`-Pcompat verify`；后续仍须逐 Tool
   单独批准 Capability、Sandbox 与 Smoke。该阶段门禁不构成任何执行授权，也不表示 R11 已完成。
