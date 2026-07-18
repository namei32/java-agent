# R8 主动运行时、Drift 与 Subagent 实施计划

- 状态：G0、A1–A6 已完成；等待阶段三套门禁与 R9 C1
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

A2 证据（2026-07-18）：先添加 `JdbcProactiveJobStoreTest`，因 Schema、Store 与稳定异常类型尚不存在而编译
失败，构成 RED。随后实现版本化 `proactive-runtime.db`、短 SQLite 事务 Claim、显式 owner/revision/lease、过期
Claim/RUNNING 恢复、过期 owner 不可提交，以及 `EVERY` 完成后只安排第一个未来 UTC 槽位（不补发已错过周期）。
目标命令 3/3 GREEN：
`./mvnw --batch-mode --no-transfer-progress -pl adapter-sqlite -am -Dtest=JdbcProactiveJobStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`。

A3 证据（2026-07-18）：先添加 `ProactiveSchedulerTest`，因 Scheduler、Settings、Step 与 Executor 不存在而
编译失败，构成 RED。随后实现单 JVM worker、短事务 Claim/Mark Running/Commit、事务外执行、恢复扫描、租约
丢失拒绝提交、父 Job 取消和有界关闭；目标测试 2/2 GREEN。

A4 证据（2026-07-18）：先添加 `ProactiveJobRunnerTest`，因 Gate、Planner、Delivery、Dedupe/Audit 类型缺失
而编译失败，构成 RED。随后实现 disabled/busy/dedupe/cooldown Gate、只允许 `SKIP|REQUESTED` 的 Planner
边界与默认 `NoOp Delivery`；busy 在 Planner 前停止，REQUESTED 不会触发外部消息投递；目标测试 3/3 GREEN。

A5 证据（2026-07-18）：先添加 Drift/Subagent 测试，因请求/结果契约与受限运行器不存在而编译失败，构成
RED。随后实现无 Tool、网络、Memory、Session、Approval、Plugin 输入的 parent-bound `SubagentRequest`，虚拟线程
超时/字符预算/父取消传播，以及只暴露 hash-only 输入和有界安全摘要的只读 Drift；目标测试 3/3 GREEN。

A6 证据（2026-07-18）：先添加 `ProactiveRuntimeTest`，因默认关闭 Properties/Runtime 不存在而编译失败，
构成 RED。随后接线 `agent.proactive.mode=DISABLED|LOCAL_SQLITE`：Disabled 不解析或创建主动 SQLite；
LOCAL_SQLITE 必须具有仅含 hash 的 AT/EVERY allowlist，启动时校验已有持久化契约；Scheduler 仅以 NoOp
Planner/Delivery 运行，并在 SQLite 提交后向 Plugin Runtime 发出 `PROACTIVE_TAP` 安全投影。目标测试 3/3 GREEN。

阶段门禁（2026-07-18）：Spotless 与 `./mvnw --batch-mode --no-transfer-progress clean verify`、
`./mvnw --batch-mode --no-transfer-progress -Pfailure verify`、
`./mvnw --batch-mode --no-transfer-progress -Pcompat verify` 均通过。compat 首轮刻意阻止了缺少
`normalization` 的新 Java-owned Proactive Fixture；补齐空 normalization、更新 Manifest SHA-256 后重跑通过。
三套测试不包含真实 Provider、Telegram、Workspace、外部源或部署 Smoke。
