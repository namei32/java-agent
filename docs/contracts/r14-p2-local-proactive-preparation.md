# R14 P2 本地主动候选与 Fake Delivery Preparation 契约

- 阶段：R14 P2-A 至 P2-C
- 状态：P2-A 至 P2-C 已完成并通过阶段门禁；仍未接线
- 日期：2026-07-19
- 前置：[R14 P0 主动、自动记忆与 Peer 边界契约](r14-proactive-peer-automation-boundaries.md)、[R14 P1 本地只读主动决策契约](r14-read-only-proactive-decision.md)、[R11 Pending Recovery Capability](pending-operation-recovery-capability.md)
- 关联 ADR：[ADR-0040：R14-P2 先局限为本地候选与 Fake Delivery Preparation](../adr/0040-restrict-r14-p2-to-local-candidate-and-fake-delivery-preparation.md)

> P2 不是外部 Source、真实发送或 Scheduler 启用。它只为后续经审批的投递 Capability 准备一个本地、同步、未接线的
> Fake 链路；任何真实网络、渠道身份、Outbox、Receipt、Provider、Memory DML 或 Bootstrap Bean 仍被禁止。

## 1. P2-A：本地候选准备

P2-A 的输入仅为已 Claim 的 `ProactiveJobLease`、`TurnCancellation`、既有 `ProactiveGate`、注入的
`FixedLocalProactiveSource`、`ReadOnlyDriftRunner` 和安全审计 Sink。其顺序固定为：

```text
cancel -> lease -> gate -> fixed local source -> read-only drift -> candidate ready
```

取消、过期 Lease、Gate Skip、空/异常 Source、干净/异常 Drift 都必须短路后续步骤。只有检测到 Drift 时，才在同一
同步调用栈内创建一个**进程内候选**。候选允许暂存已由 P0 净化的 Source 文本，以供未来 P2-B Producer 封装入
认证 Capsule；候选没有公共正文 getter，`toString()` 必须脱敏，且不得跨线程、跨进程、持久化或暴露给 HTTP/Tool。

公开结果只能是：`CANDIDATE_READY`、`SKIPPED(stableCode)` 或 `CANCELLED`。它不得包含正文、Drift 摘要、目标、
Session、job ref、Approval ref、Ledger ref 或消息内容。

## 2. P2-B：Fake Delivery Preparation

P2-B 只能消费 P2-A 当前调用内产生的候选，并把它转化为一条独立的 `proactive_prepare_delivery` Pending Operation。
它不复用 Memory Forget 的 Session/sequence：主动任务没有可安全假设的 Chat Session，因此专用
`ProactiveDeliveryOperation` 只绑定 opaque operation ref、`ProactiveJobRef`、hash-only target 和专用 Anchor。

精确绑定如下：

| 对象 | 固定规则 |
| --- | --- |
| Fake Recipient Ref | 仅注入测试用 `fake-recipient-...` 引用；不是渠道 ID、URL、chat ID 或 HTTP 参数，且永不出现在公开结果。 |
| Operation | `PENDING_APPROVAL -> APPROVED_PENDING_RESUME -> CONSUMING -> SUCCEEDED|UNKNOWN`；`COMMIT_UNREPORTED`、取消和过期均为终态。 |
| Anchor | 绑定 Operation、Job Ref、target hash、版本 `0`；Anchor 不匹配、取消或终结时零 Invoker。 |
| Capsule | schema v1；以 AES-GCM 加密并以 Operation/Approval/Anchor 作为 AAD 认证绑定；明文仅包含 Fake Recipient、净化 Source Ref/Text、Job Ref 和 target hash。 |
| Approval | 复用 R11 的 `ApprovalRequest`、Fingerprint、`EXTERNAL_SIDE_EFFECT` 风险、一次 Reservation、Ledger 与稳定 `UNKNOWN` 原则。 |

Producer 只创建 Operation、Approval、Anchor 和加密 Capsule；外部结果只投影安全 operation ref 与状态。候选先以原子
claim 限制为一次创建，并在创建时再次确认 Lease 未过期；只有确定 Store 未创建时才可释放供安全重试。它不能创建
Outbox、Receipt、Channel Client、Worker、Route 或实际消息。

## 3. P2-C：Fake Recovery 与失败闭环

P2-C 仅允许认证 Capsule + 已批准 Approval + 未过期 Anchor + 单次 Reservation 到达注入的 Fake Delivery Port。
Fake Port 不调用网络或 Channel，只返回确定的脱敏 Receipt 投影，或在故障注入下产生 `UNKNOWN`。

必须验证：未批准、取消、过期、Anchor/Capsule 不匹配、并发 Reservation、Cipher 认证失败、Fake Port 异常、审计/提交
失败和关闭均为零或单次 Fake Invoker；`UNKNOWN` 与 `COMMIT_UNREPORTED` 均不得重放。Fake Port 只返回固定安全 Receipt
code，不接收或输出渠道/网络对象。P2-C 不为 Scheduler、Bootstrap、控制面或真实渠道添加接线；其 Pending Store 是显式
Fake Port，不读取 SQLite 或生产数据。

## 4. 统一禁止项与验收

1. `agent.proactive.mode=DISABLED` 保持默认，Disabled 时零数据库、线程、ID、Provider、网络、进程和渠道 I/O。
2. P2 不读取 Python Workspace、`memory2.db`、用户数据、密钥或真实配置。
3. Source/Drift/消息正文不得出现在候选公开投影、审计、异常、日志、Fixture expected 或测试名称。
4. P2-A 完成时只运行聚焦测试；P2-B/P2-C 完成并完成文档更新后，才统一运行默认、`failure`、`compat` 三套门禁。
5. 每个真实 Source 与 Delivery Channel 均需要独立 Contract、Fake 验证和新的网络/密钥操作授权。
