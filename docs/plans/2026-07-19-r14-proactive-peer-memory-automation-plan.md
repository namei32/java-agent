# R14 主动运行、自动记忆与 Peer Agent 对齐计划

- 状态：P0、P1 与 P2-A 已完成（离线边界、未接线只读决策与本地候选准备）；P2-B–P5 未开始
- 日期：2026-07-19
- Python 证据基线：`akashic-agent` 提交 `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Java 证据基线：`agent/r12-skill-catalog`，含 R8 本地 SQLite Proactive、只读 Drift 与隔离 Subagent
- 前置：[R8 主动运行时契约](../contracts/proactive-runtime.md)、[R11 副作用候选审计](2026-07-19-r11-first-side-effect-capability-selection.md)、[完成度审计](../architecture/2026-07-19-akashic-java-completion-audit.md)

## 1. 本轮结论

R8 已建立安全骨架，不是 Python `proactive_v2`、`memory2` 或 `agent/peer_agent` 的完整替代。Java 的
`LOCAL_SQLITE` Scheduler 只接受配置中 allowlisted Job，使用租约/取消/预算，Planner 固定为 `SKIP`，Delivery 为
NoOp；Drift 只读且 Subagent 不继承 Tool、网络、Memory、Session、Approval 或 Plugin。默认 `DISABLED`。

Python 还包括主动外部源和 MCP 读取、决策/投递链、自动 Memory Optimizer、HyDE/关键词等 `memory2` 后台处理、
进程冷启动、HTTP 健康检查、远程 Agent Card/A2A 提交、异步轮询与渠道推送。这些会产生网络、子进程、持久记忆
写入或主动消息，必须分别授权。用户已确认旧 Python 记忆可弃用；本计划不迁移 `memory2.db` 或其历史数据。

## 2. 能力差距与非等价边界

| Python 能力 | Java 当前基础 | 不能声称完成的原因 | 后续处理 |
| --- | --- | --- | --- |
| `proactive_v2` Gate/Source/Judge/Resolve/Deliver 链 | R8 SQLite 租约、Gate、Scheduler 状态机 | Java 当前 Planner 永远 `SKIP`，Delivery 为 NoOp；没有来源、模型决策或真实投递 | P1/P2，逐源和逐投递分开 Contract |
| 外部/MCP Source、Presence、反馈和主动消息 | 无外部 Source；既有 MCP 只读静态 stdio | 涉及网络、SSRF、身份、数据最小化、费用、渠道身份与消息副作用 | P2，先本地 Fake Source；真实网络/渠道另行操作授权 |
| `memory2` 自动 Memorizer、Dedup、HyDE、Profile/Procedure 提取、Optimizer | Java-native 显式记忆、cosine/Hotness Context Retrieval | Python 存储、元数据、证据、检索和后台写入模型均未迁移；旧数据可弃用不等于自动写入已获授权 | P3，先确定 Java-native 写入/删除/读取 Contract；复用 R11-B2c 审批与恢复 |
| Peer Process Manager、Agent Card、A2A Tool/Poller | 受预算的单 JVM Subagent | Java Subagent 明确没有进程、网络、Tool、Session、Memory 或异步渠道结果 | P4，先身份/信任/协议/资源边界，再本地 Fake 演练 |
| Python Tool 对主动/Peer 的动态注册 | Java 静态 Catalog、Deferred Tool 解锁 | 动态 Tool/Plugin 注入不在 R8/R12 已批准边界内 | P5，先受信 Capability Manifest；不接受运行时任意注册 |

## 3. 不可跨越的安全边界

1. `agent.proactive.mode=DISABLED` 保持默认；任何新 Mode 必须严格解析，Disabled 时零数据库、线程、Provider、网络、进程或 ID 生成。
2. 计划、来源、目标、Session 和渠道身份必须是私有绑定或不透明 Ref，不能由模型、Prompt、Plugin 或外部响应覆盖。
3. 自动记忆、优化、删除、投递和远程任务提交都是副作用；它们不能因“后台”或“Proactive”而绕过 R11-B2c 的 Approval、Capsule、Ledger、Reservation、`UNKNOWN`、恢复与审计。
4. Peer Agent 的 Launcher、工作目录、环境、URL、Agent Card、输出文件和结果推送均是不受信输入；不得以 Python 的本地配置/进程管理直接复制到 Java。
5. 本阶段不访问旧 Python Workspace/数据库，不读取其 Secret，也不把用户数据送入 Provider、MCP、网络或子进程。

## 4. 解冻后的连续 TDD 顺序

### P0：主动/Peer 版本化 Contract Fixture（RED → GREEN，已完成）

已增加 28 Case 的 `proactive/r14-proactive-peer-automation-v1` Fixture、Manifest Hash 和 Kernel consumer；详细边界见
[R14 P0 Contract](../contracts/r14-proactive-peer-automation-boundaries.md)、[设计](../specs/2026-07-19-r14-proactive-peer-automation-design.md)
与 ADR-0027。它固定 Scheduler 状态/租约恢复、本地 `FIXED_LOCAL` Source 净化、仅待审批的 Decision 投影、
`NONE` Memory Mutation 及 `LOCAL_FAKE` Peer Identity/Task/Terminal。

P0 不启动真实进程或网络，也不接线 Scheduler、Source、Transport、Provider、Memory DML 或 Tool。它是 P1–P4 的
测试前置，而不是主动、自动记忆或 Peer 能力完成声明。

### P1：可审计的本地只读主动决策（GREEN，已完成）

在 R8 基础上只允许本地 Fake/预置 Source 和有界只读 Drift，生成 `SKIP` 或“待审批的请求”投影；仍不调用模型、
不写 Memory、不开启 Delivery。验证冷却、目标 busy、租约丢失、取消、崩溃恢复和关闭。

P1 已增加 15 Case 的 ReadOnlyProactiveDecision Fixture 与 failure Profile 的异常/取消测试；详细语义见
[P1 Contract](../contracts/r14-read-only-proactive-decision.md) 与
[设计](../specs/2026-07-19-r14-read-only-proactive-decision-design.md)。它顺序消费 Gate、P0 FIXED_LOCAL
Fake Source 与现有 ReadOnlyDriftRunner，只输出 SKIPPED(code)、PENDING_APPROVAL 或 CANCELLED 的无正文投影。
它不由 Bootstrap 或 ProactiveRuntime 接线，不启动 Scheduler，也不调用模型、Memory、Delivery、Transport、网络、
进程或 Peer。PENDING_APPROVAL 没有 Approval request、Capsule、Ledger、Outbox、Receipt 或执行权；R8 已有
Lease recovery、崩溃和关闭测试保持所有权。它是 P2–P4 的局部安全基础，而不是 Python proactive_v2 对齐完成声明。

### P2：外部 Source 与主动投递（分段；P2-A 已完成）

P2-A 已实现未接线的本地候选准备器：它在一个同步调用内重复 Gate、`FIXED_LOCAL` Fake Source 和只读 Drift，只有检测
到 Drift 才保留脱敏候选供未来同包 Producer 消费；公开结果不含正文、目标或引用，也不创建 Approval、Pending、Capsule、
Ledger、Outbox、Receipt 或 Delivery。12 Case Java-owned Fixture 已消费；它不是外部 Source 或主动投递实现。

P2-B 以后，每个 Source 独立定义 allowlist、DNS/私网防护、重定向、身份、缓存、正文/费用预算、脱敏审计和失败码；
每个 Delivery Channel 独立定义目标绑定、Outbox/Receipt、幂等、Approval、`UNKNOWN` 与恢复。先通过本地 Fake Transport，
再由用户单独批准真实网络/渠道 Smoke。

### P3：Java-native 自动记忆与 Optimizer（GREEN，逐 Mutation）

在用户明确决定模型是否可持久化摘要、Embedding 费用和数据保留后，才实现自动写入、合并、软失效或优化。不能迁移
Python `memory2` 的历史数据，也不能假称 HyDE/关键词/RRF/证据字段已对齐。所有 DML 必须使用 Java-native Scope、
R11-B2c Capability、幂等/Revision 和可恢复审计；先只用固定 Embedding/Fake Provider。

### P4：Peer Agent 信任与本地进程演练（GREEN）

先固定 Peer Manifest（名称、协议版本、固定本地命令、工作目录、资源/日志预算、禁止环境继承）和 Agent Card Schema。
以 Fake Process/Fake A2A Server 验证启动、健康、超时、取消、进程树、输出净化、Task Ref、轮询和关闭。网络地址、
动态 Card、真实进程命令、远程 A2A 与渠道结果推送均需独立授权。

### P5：受信 Tool 暴露与运行授权（GREEN）

只有 P1–P4 对应 Capability 已获批准后，才通过静态受信 Catalog 暴露最小 Schema。Tool 的动态注册、Plugin 注入、
后台自动推送或自动重试不因 Peer/Proactive 名义获得授权。每个真实 Provider/网络/进程/渠道 Smoke 继续按操作授权执行。

## 5. 验收与推进条件

- 每个子阶段先 RED Fixture，再 GREEN 实现；P2–P5 每个新增副作用完成后才运行默认、`failure`、`compat` 三套门禁。
- 失败测试必须覆盖：Disabled 零 I/O、拒绝未知模式、预算、取消、过期/租约、并发单获胜者、关闭、审计脱敏、`UNKNOWN` 和零重放。
- R11-B2c 的首个生产 Capability 与 R13 的渠道身份 Contract 是 P2/P3/P5 的必要前置。R12-S5 仅在同一版本要
  向模型暴露 Java-native Memory Recall 时才是前置，不能被错误地当成自动写入的替代授权；前置未完成时 R14 仍保持
  P2–P5 未实现，P0/P1 都不扩大任何运行权限。
