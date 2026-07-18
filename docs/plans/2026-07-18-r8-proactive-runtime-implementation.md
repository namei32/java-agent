# R8 主动运行时、Drift 与 Subagent 实施计划

- 状态：G0 已冻结；R7 完成后开始 A1
- 前置：R7 阶段门禁通过

1. A1：Fixture、Kernel Job/Lease/Decision/Subagent Contract。
2. A2：SQLite Schema、事务 Claim、过期租约恢复与容量。
3. A3：Application Scheduler、Gate、父取消、周期重排与关闭。
4. A4：Proactive Planner/NoOp Delivery，冷却、busy、dedupe、审计。
5. A5：Drift 只读与无 Tool Subagent，预算/取消/结果投影。
6. A6：默认关闭 Bootstrap、故障矩阵、严格阶段门禁。

禁止：真实外部内容源、自动推送、真实模型、真实 Telegram、Workspace 写入、Side Effect Tool、Peer Agent、
分布式 Scheduler。通过 A6 后才可进入 R9 的离线演练工具。
