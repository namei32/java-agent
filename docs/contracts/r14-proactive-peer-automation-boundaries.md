# R14 P0 主动、自动记忆与 Peer 边界契约

- 阶段：R14 P0
- 状态：已实现为离线 Kernel Contract；运行时仍未启用
- 日期：2026-07-19
- 关联 ADR：[ADR-0027](../adr/0027-freeze-r14-proactive-peer-automation-boundaries.md)
- Fixture：`testdata/golden/proactive/r14-proactive-peer-automation-v1.json`
- 前置：[R8 主动运行时契约](proactive-runtime.md)、[R11 副作用安全契约](tool-approval-side-effect-safety.md)

## 1. 范围

本契约为 R14 后续切片固定可验证的最小安全边界。它不读取 Python、不会启动 R8 Scheduler，也不创建任何数据库、线程、
Provider、网络连接、进程、消息、Tool 或 Memory Mutation。所有字段均为 Java-owned 测试/Kernel 值，不能被解释为
Python `proactive_v2`、`memory2` 或 `peer_agent` 的兼容接口。

## 2. Scheduler 状态与租约恢复

允许的持久状态转移为：

```text
SCHEDULED -> CLAIMED -> RUNNING -> SUCCEEDED|SKIPPED|FAILED|CANCELLED
                |          |
                +----------+---- expired lease -> SCHEDULED

SCHEDULED -> CANCELLED
```

终态不能返回 `RUNNING` 或任何非终态。`SCHEDULED -> SUCCEEDED` 不合法；这防止绕开 Lease、Gate、预算、取消和审计。
R8 Store/Scheduler 继续拥有实际 Claim、Recovery 和 Commit 事务；P0 的 `ProactiveJobTransition` 只冻结转换表，不取代
该持久化实现。

## 3. 本地 Fake Source

`ProactiveSourceItem` 只接受 `FIXED_LOCAL`、小写 opaque `sourceRef` 和最多 4,000 code points 的文本。它把 CRLF/CR
规范为 LF，拒绝其他 C0 控制字符、空文本和 `http://`/`https://`。文本仍是不可信数据，不是 Prompt 指令、URL、文件路径、
MCP 资源或可执行命令。

`MCP`、HTTP、文件、Webhook、Provider 和真实 Presence 均不属于该 enum；P2 必须为每一种来源独立指定 DNS/私网、
重定向、身份、缓存、正文/费用预算、脱敏审计、失败码和取消语义。

## 4. Decision、Delivery 与 Memory

`ProactiveDecision.REQUESTED` 仅被投影为 `PENDING_APPROVAL`，`SKIP` 为 `NOT_REQUESTED`。两者的
`allowsExternalDelivery()` 与 `transportAuthorized()` 均恒为 `false`。它们不能调用现有渠道 Delivery、创建 Outbox、
发送消息、写入 Receipt，或替代 R11-B2c Capability。

`ProactiveMemoryMutation` 仅有 `NONE`。任何 `WRITE`、`DELETE`、未知或大小写变体必须以
`MEMORY_MUTATION_FORBIDDEN` 拒绝。它不影响用户显式 Java-native Memory 管理 API 或 R12-S5 的只读召回；自动
Memorizer、Dedup、Optimizer、Embedding 费用和数据保留仍待 P3 的独立决策。

## 5. Peer 表示边界

P0 仅表示 `LOCAL_FAKE` 的 `PeerIdentity`、`peer-task-...` opaque task reference，以及
`PENDING|RUNNING|SUCCEEDED|FAILED|CANCELLED` 终态。该身份不能包含 URL、命令、目录、环境、Card、进程 ID 或任务正文；
remote/未知 trust、URL 和不合规引用统一以 `PEER_CONTRACT_INVALID` 拒绝。

这不是进程 Launcher、A2A Client、Poller 或 Tool。P4 必须先固定 manifest、资源/日志预算、进程树、健康、取消、输出净化
和恢复，再在 Fake Process/Fake A2A 下验证。

## 6. 验收

版本 `1` Fixture 固定 28 个场景：7 个 Scheduler 状态、6 个 Source、3 个 Decision/Delivery、4 个 Memory Mutation、
8 个 Peer 场景。`ProactivePeerAutomationContractFixtureTest` 必须消费每一例；Fixture SHA-256 受 Golden Manifest 保护。
本切片的 RED 是缺少这些类型时无法编译；GREEN 是所有场景通过，且未增加 Bootstrap 接线。
