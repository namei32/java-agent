# ADR-0027：先冻结 R14 主动、记忆与 Peer 自动化边界

- 状态：已接受；不启用任何运行时能力
- 日期：2026-07-19
- 决策范围：R14 P0 离线 Contract Fixture 与 Kernel 值对象

## 背景

Akashic 的 `proactive_v2` 能读取 MCP/外部内容、判断并主动投递；`memory2` 会在后台写入和优化记忆；
`peer_agent` 还会启动进程、读取 Agent Card、发送 A2A 请求和异步回推。R8 的 Java Scheduler 则只接受
hash-only allowlisted Job，Planner 固定安全决策，Delivery 为 NoOp，且默认关闭。

在没有逐项网络、进程、记忆 DML、渠道投递和审批/恢复授权前，直接把这些 Python 表面接到 Java Runtime 会把不受信
输入、持久副作用和外部不确定性混为一条路径。因此 R14 先需要可执行的边界，而不是一条看似可用的空实现。

## 决策

R14 P0 固定一个 Java-owned、版本化的 28 Case Fixture，并只增加不可执行的 Kernel Contract：

1. Scheduler 只能从 `SCHEDULED` 经租约/运行进入终态；租约过期只可回到 `SCHEDULED`，终态不可重开。
2. Source 只可表示有界、无 URL、控制字符拒绝的 `FIXED_LOCAL` 投影。它不是 MCP、HTTP、文件或 Provider 输入。
3. `REQUESTED` 只映射为 `PENDING_APPROVAL` 投影；它永远不授权 Transport、Outbox 或外部投递。
4. 自动 Memory Mutation 只有 `NONE`；`WRITE`、`DELETE` 和大小写变体均稳定拒绝。
5. Peer 只可表示无 URL/命令/环境/Agent Card 的 `LOCAL_FAKE` 身份、opaque task ref 和终态；远端信任、真实进程、
   A2A 和 Poller 不可表示。

这些值对象不注册配置、线程、数据库、Provider、网络、Tool、Channel 或 Plugin；现有 `ProactiveRuntime` 仍保持原有
`DISABLED` 默认与 `LOCAL_SQLITE`/NoOp 范围。

## 后果

P1 可以在这些稳定类型上接入预置 Fake Source 和只读 Drift，但仍只能生成 `SKIP` 或待审批投影。P2 的每个外部 Source
和 Delivery、P3 的每个 Memory Mutation、P4 的每个 Process/A2A 组件都必须另立 Capability、Approval、Ledger、
`UNKNOWN`、恢复和真实 Smoke Contract。本 ADR 绝不构成它们的实现或操作授权。
