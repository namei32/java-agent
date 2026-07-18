# 项目文档导航

本目录记录 Namei Agent Java 的架构约束、迁移路线、行为契约、已批准设计、实施计划和运行手册。Python 仓库是兼容性基准，但 Java 项目的有效决策以本目录和根目录 `AGENTS.md` 为准。

## 当前状态

- 已完成：七模块 Maven Reactor、同步 HTTP 被动聊天、历史恢复、OpenAI-compatible 模型适配、SQLite 原子轮次写入、失败语义、单进程同会话串行化、健康检查与基础可观察性。
- 已完成：MVP Minor 技术债加固和 Java 重写文档治理。
- 已完成：被动聊天共同投影的 Python/Java Golden Test 规范、历史、Prompt、SQLite、错误映射基准及 CI 门禁。
- 已完成：Python/Java 配置兼容 Contract、配置 Golden、TomlJ Parser 选型、只读 Resolver、Spring Boot 启动装配和无副作用检查入口。
- 已完成：核心消息、生命周期和 Tool Contract，Tool Message/最小循环 Golden，以及带 `current_time` 的只读最小 Tool Loop。
- 已完成：Tool Runtime 安全契约实现，包括模式、预算、Schema、Arguments/Result 边界、超时、并发许可、取消和安全 Golden。
- 已完成：审批参数指纹、整批门禁、一次性消费、幂等/`UNKNOWN`、安全生命周期、Approval Golden、生产 Deny All 装配，以及 R3.2 默认、`failure`、`compat` 与依赖/安全阶段门禁。
- 已完成：R4.1 只读 Markdown Profile、Context Frame、Retrieval Port/NoOp、Golden、默认关闭装配、安全错误映射和阶段门禁。
- 已完成：R4.2 Java 原生语义记忆的 Task J1–J12，包含 Java-owned Fixture、Kernel 协议、版本化 Schema/Float32 Codec、SQLite Store/Mutation 幂等、Spring AI Embedding、显式 Memory API、cosine/Hotness/Scope 检索、Chat/Context Frame、默认关闭装配、Contract/Failure 验收与最终门禁；生产默认继续保持 `DISABLED`。
- 已完成：R5.1 默认关闭、静态配置、stdio-only 的 MCP 只读客户端，包含官方 SDK 隔离、自有有界 Transport、分页发现、稳定命名、安全 Schema 投影、Wire Cancellation、Stale/单次重连、Chat 闭环、Bootstrap 装配和 Java Reference Server 验收；生产与模板仍为 `DISABLED`。
- 已完成：R6.1 版本化渠道消息 Contract Runtime，包含 Java-owned Fixture、`InboundMessage`/`OutboundMessage`、严格序号与唯一终态、取消原因透传、有界背压、稳定安全错误和现有 Chat 终态投影。
- 已完成：R6.2 Provider Streaming 与本地 CLI，包含纯 JDK 流式 Port、Application Delta/Tool Loop、Spring AI OpenAI-compatible SSE Adapter、有界 CLI、传输取消、提交隔离以及默认、`failure`、`compat` 最终门禁。
- 已完成并合入 `main`：R6.3 Telegram Channel Host、JDK Bot API 长轮询、数值 Allowlist、终态合并、定向取消、有界网络/关闭和默认零网络装配已通过 PR #6；真实 Smoke 与部署仍未授权。
- 已完成并合入 `main`：R6.4 独立 Java SQLite 渠道账本、Inbox/Turn Claim、事务 Outbox、Receipt、`UNKNOWN`、恢复、清理、容量门禁和备份/回退演练；PR #7 与合并后聚焦稳定性 PR #8 的远程三套门禁均通过，真实 Smoke 仍待授权。
- 已完成当前授权范围：R6.5 默认关闭、仅 Loopback 的认证控制面后端 G0–G10，包括 48 Case Java-owned Fixture、活动 Turn Registry、独立有界 Event Hub、Telegram Volatile/SQLite Reliable 接入、进程内 Bearer Session、安全状态/取消 API、future-only SSE 和故障矩阵；Draft PR #9 首轮远程三套 CI 全绿，本地高风险 Review 修复正在执行阶段门禁，尚未推送、转 Ready 或合并。
- 尚未覆盖：自动 Memory 写回/Optimizer、真实 Embedding/真实 Workspace 启用、可用的人类审批渠道、生产 Durable Ledger、真实副作用工具、真实 Telegram Smoke、经单独授权的真实 Provider 流式 Smoke、真实 MCP Server/Streamable HTTP、插件、主动任务、Drift 和 Subagent。

完整进度与阶段门禁见 [Java 重写 Roadmap](roadmap/java-rewrite-roadmap.md)，逐项能力状态见 [Python/Java 能力差距矩阵](architecture/python-java-capability-matrix.md)。

## 阅读顺序

1. [Java 重写指南](architecture/java-rewrite-guide.md)：目标架构、迁移原则和兼容策略。
2. [Java 重写 Roadmap](roadmap/java-rewrite-roadmap.md)：阶段顺序、交付物和退出门禁。
3. [Python/Java 能力差距矩阵](architecture/python-java-capability-matrix.md)：当前覆盖范围与缺口。
4. [架构决策记录](adr/README.md)：已经固定的关键技术决策。
5. 与当前变更相关的 `contracts/`、`specs/` 和 `plans/`。
6. [本地开发运行手册](runbooks/local-development.md)。

当前 Golden 资产从 [Python/Java Golden Test 夹具规范](contracts/golden-test-fixtures.md)进入，包含配置、历史、Prompt、只读 Context/Memory、SQLite、错误映射、Tool Loop、Tool Runtime 安全和 Approval/Side Effect 基准；R4.2、R5.1、R6.1–R6.5 另有不依赖 Python 的 Java-owned Semantic Memory、MCP、版本化渠道消息、Provider Streaming/CLI、Telegram、可靠投递和 Loopback 控制面 Contract Fixture。
配置迁移边界从 [Python/Java 配置兼容契约](contracts/python-java-configuration.md)进入，实际启动与检查命令见[本地开发运行手册](runbooks/local-development.md)。
工具迁移边界从 [核心消息、生命周期与 Tool 契约](contracts/core-message-lifecycle-tool.md)进入。
Tool Runtime 的模式、预算、校验、超时、取消和 Provider 发布门禁以 [Tool Runtime 安全契约](contracts/tool-runtime-safety.md)为准。
R3.2 的批准边界从 [Tool 审批、副作用、幂等与沙箱安全契约](contracts/tool-approval-side-effect-safety.md)进入，实现与验证历史见 [Tool Approval Framework 设计](specs/2026-07-14-tool-approval-framework-design.md)和[实施计划](plans/2026-07-14-tool-approval-framework-implementation.md)。当前生产只装配 Deny All；Framework 不等于人类审批可用，也不授权任何真实副作用工具。
R4.1 的批准边界见 [只读上下文与记忆兼容契约](contracts/read-only-context-memory.md)、[设计](specs/2026-07-14-read-only-context-memory-design.md)和[实施计划](plans/2026-07-14-read-only-context-memory-implementation.md)。
R4.2 的已批准边界见 [Java 原生语义记忆、持久化与优化器契约](contracts/semantic-memory-persistence-optimizer.md)、[ADR-0005](adr/0005-use-java-native-semantic-memory-store.md)、[设计](specs/2026-07-15-java-native-semantic-memory-design.md)和[实施计划](plans/2026-07-15-java-native-semantic-memory-implementation.md)。旧 Python `memory2.db` 不读取、不迁移、不自动删除；新库通过显式 API 写入，自动提取与 Optimizer 不实施。
R5.1 的已实现边界见 [MCP 只读客户端契约](contracts/mcp-client-tool-runtime.md)、[ADR-0006](adr/0006-use-official-mcp-java-sdk.md)、[设计](specs/2026-07-15-mcp-read-only-client-design.md)和[实施计划](plans/2026-07-15-mcp-read-only-client-implementation.md)。该阶段只授权受控 Java Reference Server；真实 MCP Server、网络、Secret、HTTP Transport 和副作用 Tool 仍需分别批准。
R6.1 的已实现边界见 [版本化渠道消息与流式运行时契约](contracts/versioned-channel-message-runtime.md)、[ADR-0007](adr/0007-use-project-owned-bounded-channel-message-protocol.md)、[设计](specs/2026-07-15-versioned-channel-message-runtime-design.md)和[实施计划](plans/2026-07-15-versioned-channel-message-runtime-implementation.md)。该切片建立 Channel Contract Runtime；R6.2 已在其上完成本地 CLI 与 Provider Streaming Adapter，R6.3 已完成 Telegram Adapter 的离线实现验收。

R6.1 至 R6.6 的完整阶段拆分、全局不变量、门禁、暂停条件和合并顺序见 [R6 渠道、消息总线与控制面总体工作计划](plans/2026-07-15-r6-channel-message-control-plane-master-plan.md)。总体计划已批准并进入实施；每个子阶段仍须先冻结自己的 Contract、Spec、ADR 和实施计划，真实网络、Secret 与付费 Smoke 保留独立授权门禁。

R6.2 的已实现边界见 [Provider Streaming 与本地 CLI 契约](contracts/provider-streaming-cli.md)、[ADR-0008](adr/0008-use-project-owned-synchronous-stream-observer.md)、[设计](specs/2026-07-15-provider-streaming-cli-design.md)和[实施计划](plans/2026-07-15-provider-streaming-cli-implementation.md)。该阶段只实现本地 CLI 与 Provider 文本流；全部自动化使用本地 HTTP Stub，不授权真实渠道、真实 Secret 或默认真实 Provider Smoke。

R6.3 的已实现边界见 [Telegram Channel Host 契约](contracts/telegram-channel-host.md)、[ADR-0009](adr/0009-use-jdk-httpclient-for-telegram-long-polling.md)、[设计](specs/2026-07-16-telegram-channel-host-design.md)和[实施计划](plans/2026-07-16-telegram-channel-host-implementation.md)。JDK HTTP 长轮询、数值身份、Secret 延迟读取和纯离线纵向切片已通过本地三套门禁；真实 Token/网络/用户数据和部署始终是后续独立门禁。

R6.4 的已实现边界见 [渠道可靠投递、幂等与恢复契约](contracts/channel-reliable-delivery.md)、[ADR-0010](adr/0010-use-dedicated-sqlite-channel-ledger.md)、[设计](specs/2026-07-16-channel-reliable-delivery-design.md)、[实施计划](plans/2026-07-16-channel-reliable-delivery-implementation.md)和[备份/回退手册](runbooks/channel-ledger-backup-rollback.md)。本地自动化只使用临时 Java/SQLite 与 Loopback；不得自动重跑 Turn、重发 `UNKNOWN` 或访问真实 Telegram。远程 CI 不构成真实网络或部署授权。

R6.5 的批准边界见 [Loopback 控制面契约](contracts/loopback-control-plane.md)、[ADR-0011](adr/0011-use-authenticated-sse-for-loopback-control-events.md)、[设计](specs/2026-07-17-loopback-control-plane-design.md)和[实施计划](plans/2026-07-17-r6-loopback-control-plane-implementation.md)。后端 G0–G10 已完成当前授权范围，Draft PR #9 首轮远程 CI 全绿，本地 Review 修复待阶段门禁和发布；远程控制、CLI+Web、同步 Chat 取消、Ledger Reconcile、真实 Telegram 和前端实现继续冻结，后端合入后才进入 G11 前端 Contract。

## 目录职责

| 目录 | 内容 | 是否描述当前事实 |
| --- | --- | --- |
| `architecture/` | 总体架构、迁移指南、能力盘点 | 是 |
| `roadmap/` | 跨里程碑计划和阶段门禁 | 是 |
| `adr/` | 重要且长期有效的架构决策 | 是；被替代时显式标记 |
| `contracts/` | 外部 API、事件和数据兼容契约 | 是 |
| `specs/` | 单项功能或治理设计 | 以文首状态为准 |
| `plans/` | 可执行任务、验证证据和历史记录 | 以文首状态为准 |
| `runbooks/` | 开发、启动、排障和运维步骤 | 是 |

## 文档状态规则

- `草案`：仅供讨论，不能据此实现有兼容性影响的行为。
- `已批准`：可以进入实施。
- `实施中`：已经开始落地，完成情况以任务清单为准。
- `已实现并验证`：实现和规定门禁均通过。
- `已废弃`：不得继续作为实施依据，并必须指向替代文档。

已经完成的 Spec 和 Plan 保留为决策与验证历史，不把它们改写成 Roadmap。Roadmap 只维护阶段级事实；具体行为仍以 Contract、Spec 和 ADR 为准。
