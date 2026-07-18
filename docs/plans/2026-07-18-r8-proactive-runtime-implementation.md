# R8 主动运行时、Drift 与 Subagent 实施计划

- 状态：G0、A1 已完成；等待 A2 连续 TDD
- 前置：R7 阶段门禁通过

1. A1：Fixture、Kernel Job/Lease/Decision/Subagent Contract。
2. A2：SQLite Schema、事务 Claim、过期租约恢复与容量。
3. A3：Application Scheduler、Gate、父取消、周期重排与关闭。
4. A4：Proactive Planner/NoOp Delivery，冷却、busy、dedupe、审计。
5. A5：Drift 只读与无 Tool Subagent，预算/取消/结果投影。
6. A6：默认关闭 Bootstrap、故障矩阵、严格阶段门禁。

禁止：真实外部内容源、自动推送、真实模型、真实 Telegram、Workspace 写入、Side Effect Tool、Peer Agent、
分布式 Scheduler。通过 A6 后才可进入 R9 的离线演练工具。

A1 证据（2026-07-18）：先添加 16 Case Java-owned `proactive/proactive-runtime-v1` Fixture 和
`ProactiveContractTest`；目标测试因缺少主动 Job、Schedule、Decision、稳定码和 Subagent Budget 类型而
编译失败，构成有效 RED。随后以纯 Kernel 值对象固定 AT/EVERY、64 位安全引用、尝试预算、可跳过 Gate、
子代理字符/步骤/时间上限与脱敏 JobRef；同一目标命令 1/1 GREEN。命令：
`./mvnw --batch-mode --no-transfer-progress -pl agent-kernel -am -Dtest=ProactiveContractTest -Dsurefire.failIfNoSpecifiedTests=false test`。
