# R14 主动运行、自动记忆与 Peer Agent 对齐计划

- 状态：P0–P5 已完成离线、默认关闭的 Contract/Fake 切片；P6 已完成仅测试生命周期的临时 SQLite NOTE 写入演练并通过阶段完整门禁；均不等价真实自动化或 Python 全量对齐
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

### P2：外部 Source 与主动投递（分段；本地 Fake 链已完成）

P2-A 已实现未接线的本地候选准备器：它在一个同步调用内重复 Gate、`FIXED_LOCAL` Fake Source 和只读 Drift，只有检测
到 Drift 才保留脱敏候选。P2-B 已将仍有效且仅被原子 claim 一次的候选转为独立的 Approval、job/target-hash Anchor 与
AES-GCM Capsule；P2-C 只允许已批准、未过期的单次 Reservation 调用注入的 Fake Delivery Port，并在不确定时冻结为
`UNKNOWN` 或 `COMMIT_UNREPORTED`。P2-A 的 12 Case、P2-B 的 8 Case Fixture 与 P2-C 的成功/失败/并发测试都不接线
Bootstrap、SQLite、Scheduler、Outbox、网络、渠道、Receipt Adapter 或自动 Memory 写入；它们不是外部 Source 或真实
主动投递实现。

P2-B 以后，每个 Source 独立定义 allowlist、DNS/私网防护、重定向、身份、缓存、正文/费用预算、脱敏审计和失败码；
每个 Delivery Channel 独立定义目标绑定、Outbox/Receipt、幂等、Approval、`UNKNOWN` 与恢复。先通过本地 Fake Transport，
再由用户单独批准真实网络/渠道 Smoke。

### P3：Java-native 自动记忆与 Optimizer（GREEN，逐 Mutation）

P3 现只实现 `proactive_memory_capture` 的本地 Fake、逐 Mutation 闭环：有效 `FIXED_LOCAL` 候选一次 claim 后创建独立
Pending/Approval/job-target Anchor/AES-GCM Capsule；只有显式测试恢复、已批准、未过期、认证和唯一 Reservation 同时成立时，
才会调用注入的 Fake Java-native Memory Port。Port/审计/Ledger 失败冻结为 `UNKNOWN`，Anchor 提交未报告为
`COMMIT_UNREPORTED`，均不可重放。Fixture 与恢复测试覆盖取消、过期、篡改和并发；没有 Adapter、SQLite、Memory DML、
模型摘要、Embedding、Worker 或 Bootstrap 接线。

P3 的固定 `fake-r14-memory-v1` 只是 Port 边界标识，不调用实际 Embedding。它不迁移 Python `memory2` 历史，也不实现
HyDE、关键词、RRF、正文合并、软失效、分类或删除。每一种后续 Mutation 与任何真实 Java-native DML 均要单独新增
Capability、Scope/保留、Approval、幂等、恢复与数据授权 Contract。

### P4：Peer Agent 信任与本地进程演练（GREEN）

P4 只接受 `LOCAL_FAKE` / `peer-local-fake`、协议 `local-fake-peer-v1`、版本 1、1,024 code points 输出、5 秒超时和
单并发的静态 Manifest/Card。它实现独立 Pending/Approval/Anchor/AES-GCM Capsule 与显式 Recovery；Fake Port 只接收
无正文的静态 Card。Fixture 和恢复测试验证输出控制字符净化、未批准/过期/篡改零调用、运行中取消仅调用 Fake `cancel`、
单 Reservation、失败 `UNKNOWN`/`COMMIT_UNREPORTED` 与零重放。

它没有本地命令、工作目录、环境、PID、进程树、文件、Socket、HTTP、A2A Server、健康检查、轮询或 Bootstrap 接线。
真实进程、动态/远程 Card、远程 A2A 和渠道结果推送仍需独立授权。

### P5：受信 Tool 暴露与运行授权（GREEN）

P5 只增加 `request_proactive_memory_capture`（`WRITE`）与 `request_local_fake_peer_task`
（`EXTERNAL_SIDE_EFFECT`）两个空参数、`DEFERRED` Builtin Schema。默认 Toolset 为空；只有 Catalog 测试构造才可被
`tool_search` 解锁，且 Placeholder 固定返回“工具不可用”。它没有 Producer、Pending、Approval、Capsule、Anchor、Port 或
配置接线，因此 Schema 绝不等于执行权。

动态注册、Plugin/MCP 注入、后台自动推送/重试，以及任何真实 Provider/网络/进程/渠道 Smoke 都继续按操作授权执行。

### P6：Java-native NOTE 写入演练（已实现，未接线）

P6 选择新的 `proactive_memory_note_write` Capability，而不是把 P3 Fake Port 静默改为生产 DML。它只准在 JUnit
临时目录的 Java-owned `agent-memory.db` 使用 Fake Embedding 与既有 `JdbcJavaMemoryStore` 演练一个经审批的
`FIXED_LOCAL` `NOTE` 写入、幂等、`UNKNOWN`、`COMMIT_UNREPORTED` 与事务回退；临时库在测试结束删除。它没有 Bootstrap、配置、Tool、
Worker、网络、真实 Workspace 或长期保留权限。详细边界见 [P6 Contract](../contracts/r14-p6-approved-proactive-memory-note-write-rehearsal.md)
与 [实施计划](2026-07-20-r14-p6-proactive-memory-note-write-implementation.md)。

## 5. 验收与推进条件

- 每个子阶段先 RED Fixture，再 GREEN 实现；P3–P5 已完成聚焦验证及阶段末默认、`failure`、`compat` 三套完整门禁。
- 失败测试必须覆盖：Disabled 零 I/O、拒绝未知模式、预算、取消、过期/租约、并发单获胜者、关闭、审计脱敏、`UNKNOWN` 和零重放。
- R11-B2c 的首个生产 Capability 与 R13 的渠道身份 Contract 是 P2/P3/P5 的必要前置。R12-S5 仅在同一版本要
  向模型暴露 Java-native Memory Recall 时才是前置，不能被错误地当成自动写入的替代授权；P3–P5 虽已实现本地 Fake
  Contract，仍不扩大任何 Bootstrap/生产运行权限。
