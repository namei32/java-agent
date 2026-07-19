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
- 已完成并合入 `main`：R6.5 默认关闭、仅 Loopback 的认证控制面后端 G0–G10，包括 48 Case Java-owned Fixture、活动 Turn Registry、独立有界 Event Hub、Telegram Volatile/SQLite Reliable 接入、进程内 Bearer Session、安全状态/取消 API、future-only SSE 和故障矩阵；PR #9 与合并提交 `dbaf272` 的主分支三套 CI 均全绿。
- 已完成并验证：R7 受信 Java `ServiceLoader` SPI、隔离 stdio Plugin Bridge、观察型 Hook 与默认关闭装配；R8 本地 SQLite 租约 Scheduler、hash-only allowlist、受限 Proactive Gate/NoOp Delivery、只读 Drift、parent-bound Subagent、完成后安全 Plugin Tap 与默认关闭装配；R9 仅 sandbox 的状态/资格/备份 Manifest/差异、预 Spring CLI 和回退演练。三阶段均通过默认、`failure`、`compat` 门禁，且不访问真实网络、Provider、Telegram 或 Workspace 写入。
- 已完成并验证：R8 后续安全可观测性切片的[本地 Proactive Job 只读检视](contracts/read-only-local-proactive-job-inspection.md)。它仅在 `READ_ONLY`、`ACTIVE_RUNTIME` 与既有 `LOCAL_SQLITE` Runtime 同时成立时，经当前 Turn `tool_search` 暴露 Deferred `list_local_proactive_jobs`；13 个 Java-owned Fixture 场景和默认、`failure`、`compat` 门禁已通过。它不等价 Python `list_schedules`，也不创建、取消、投递或暴露任何渠道、内容、身份或 hash/key。
- 已完成并验证：R10 的 Java-owned Prompt Fixture、版本化 Section/Frame、code point 预算及固定裁剪、classpath Akashic Core Persona、严格 `agent.prompt` Properties、HTTP/CLI/Telegram 可信时间/会话 Context 与只读 Memory 接线。默认继续为 `MINIMAL`；`AKASHIC_CORE` 必须显式启用；三套阶段门禁均已通过。
- 已完成并验证：R10-P0 的 [Provider 失败分类 Contract](contracts/r10-provider-failure-classification.md) 将安全拒绝与上下文超限投影为脱敏、不可重试的稳定码；9 个生产消费的 Fixture Case 覆盖同步/流式、Cause、Timeout 优先级、未知失败与 HTTP 脱敏。R10-P1 的[受信 Provider Options Contract](contracts/r10-trusted-provider-options.md)默认 `DISABLED`，只允许固定 DeepSeek/DashScope thinking/effort allowlist。P2 的[有界 reasoning/Tool continuation Contract](contracts/r10-reasoning-tool-continuation.md)只在显式 `DEEPSEEK` + `SAFE_LOCAL` 时将最多 16,384 code points 的 reasoning 保留在单个内存 Tool Loop，并在紧随的 assistant Tool Call 回放；它绝不进入 Session、SQLite、日志、生命周期、Channel 或 HTTP/CLI。P3 的[上下文超限安全恢复 Contract](contracts/r10-context-limit-recovery.md)默认 `DISABLED`，仅 `SAFE_LOCAL` 的非流式、任何 Tool 执行前允许在固定 Prompt/History 候选间本地重试，绝不重放 Tool/副作用或改写历史。P4 的[匿名缓存用量观察 Contract](contracts/r10-provider-cache-usage-observation.md)只从标准 prompt/cache-read 数字形成匿名 Turn 聚合，且仅在 Session 提交后交给默认 no-op Port；不读取 native Usage、不留 Session/模型/内容，也不建立 Dashboard、SQLite、日志或 Web API。上述受限切片均通过默认、`failure`、`compat` 门禁；但 P2b 的跨 Turn DeepSeek reasoning 历史与空占位符仍未对齐，须先取得数据保留与请求扩展批准。全部自动化只用 Fake 或本地 Stub。
- 已完成并验证：R11 B1 Tool Catalog、B2a Local Approval Inbox Foundation，以及 B3 默认关闭的只读 Workspace Tool。B3 只允许独立显式 Root 内的 `read_file`/`list_dir`，具备逐段链接拒绝、严格 UTF-8、固定上限、稳定安全码与 Deferred Schema；它不读取 `${agent.workspace}`、不写入、不恢复 Turn，也不调用副作用 Tool。
- 已完成并验证：R11-B4 的[当前会话只读证据 Tool](contracts/read-only-conversation-evidence-tools.md)，将 Python `fetch_messages`/`search_messages` 的受限用途迁移为当前 Session 的 opaque `msg-v1:<seq>` 查询。它默认 `DISABLED`，仅双重 `READ_ONLY` 时经本 Turn `tool_search` 作为 Deferred Tool 出现；不暴露 Session/Route/内部元数据，不写入，也不搜索跨会话历史。
- 已完成并审计：R11 B2b 的[待审批 Tool Operation、参数胶囊与恢复安全契约](contracts/pending-tool-operation.md)和[ADR-0018](adr/0018-use-single-transaction-pending-operation-store.md)提供不透明引用、状态优先级、AES-256-GCM、同库 `CONSUMED`/`CONSUMING`/唯一 `RESERVED`、Ledger、Session Anchor 与零重放基础。R11-B2c 已按获批的[Scope 受限 Memory Forget Capability](contracts/approved-scope-bound-memory-forget.md)实现默认关闭的批量软失效、加密 Capsule、显式恢复、SQLite 取消、严格 Loopback Resume/Cancel/Status 与 24 Case Fixture 归属，并通过默认、`failure`、`compat` 三套门禁；它仍没有 `forget_memory` Tool/Chat 注册、Worker、自动 Resume、真实数据或网络执行。
- 已完成首个副作用 Capability 候选审计：[R11-B2c 选择记录](plans/2026-07-19-r11-first-side-effect-capability-selection.md)确认 Python `forget_memory` 的批量软失效与 Java 当前 Scope 物理删除并不等价；用户已选择并批准 Java 的当前 Scope、无正文安全差异。其余副作用 Tool 继续为 Deny All，且不注册任何 Tool。
- 已完成并验证：R12-S1 的[只读 Skill Catalog](contracts/read-only-skill-catalog.md)，包含 Kernel Port、显式 Root 的 Markdown Adapter、Workspace 覆盖/依赖可用性、无路径 Catalog 与 `AKASHIC_CORE` always 注入；13 Case Fixture 和三套完整门禁均通过。默认仍为 `DISABLED`，不执行 Skill、Tool、脚本或网络。
- 已完成并验证：R12-S3 的[Plugin 生命周期只读映射](contracts/plugin-lifecycle-read-only-mapping.md)，以 API v2 `LIFECYCLE_TAP` 精确投影已支持的 Java Phase；旧 API v1 Wire 保持不变，外部 stdio Plugin 必须显式配置 `api-version: 2`。它仍默认 `DISABLED`、异步隔离，且不提供 Gate、Tool/Channel 注入、Python import 或真实外部 Plugin 启用。
- 已完成并验证：R12-S4 的[按需只读 Skill 正文 Tool](contracts/read-only-skill-content-tool.md)，只在 Skill/Tool 双重 `READ_ONLY` 后作为 deferred `read_skill` 出现，须经当前 Turn `tool_search` 解锁；它只回送可用且已审计的无 frontmatter 正文，不暴露路径或执行任何 Skill。[ADR-0029](adr/0029-treat-skills-as-instructional-assets-not-an-execution-runtime.md)进一步确认 Python 的 Skill 也是 Markdown 指令而非可迁移执行器，文中建议的动作仍须逐 Tool 单独授权。
- 已完成并验证：R12-S5 的[当前 Scope 只读记忆召回 Tool](contracts/read-only-memory-recall-tool.md)，以三重默认关闭条件和当前 Turn `tool_search` 暴露受限 `recall_memory`；它只读取 Java Native 当前 Scope，结果不含 Session/Scope/模型/检索 trace，且没有写入或真实 Provider 启用。
- 已完成：R14-P0 的[主动、自动记忆与 Peer 边界 Contract](contracts/r14-proactive-peer-automation-boundaries.md)，以 28 Case Fixture 固定 Scheduler/租约状态、本地 Fake Source、待审批 Delivery 投影、`NONE` Memory Mutation 和 `LOCAL_FAKE` Peer；它没有接线线程、数据库、网络、进程、投递或自动记忆。
- 已完成：R14-P1 的[本地只读主动决策 Contract](contracts/r14-read-only-proactive-decision.md)，以 15 Case Fixture 和 failure 测试串联 Gate、Fake Source、只读 Drift 与无正文 skip/pending/cancel 投影；它不创建审批、投递、记忆写入或任何 Bootstrap Runtime。
- 已冻结：R13-C0 的[Loopback 只读控制索引 Contract](contracts/r13-read-only-control-index.md)及 20 Case Fixture，规定未来索引的认证、最小投影、排序、分页、opaque cursor 与脱敏；当前零 Controller、Route、历史读取、前端或写入。
- 尚未覆盖：自动 Memory 写回/Optimizer、真实 Embedding/真实 Workspace 启用、可恢复 Pending Turn、生产 Durable Side Effect Ledger、真实副作用工具、真实 Telegram Smoke、经单独授权的真实 Provider 流式 Smoke、真实 MCP Server/Streamable HTTP，以及任何真实生产切换。

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

R6.5 的批准边界见 [Loopback 控制面契约](contracts/loopback-control-plane.md)、[ADR-0011](adr/0011-use-authenticated-sse-for-loopback-control-events.md)、[设计](specs/2026-07-17-loopback-control-plane-design.md)和[实施计划](plans/2026-07-17-r6-loopback-control-plane-implementation.md)。后端 G0–G10 和本地高风险 Review 修复均已完成并通过阶段门禁；PR #9 已合入 `main`，其后主分支三套 CI 已全绿。远程控制、CLI+Web、同步 Chat 取消、Ledger Reconcile、真实 Telegram 和前端实现继续冻结。

R7–R9 已按用户授权完成并通过完整阶段门禁：R7 的[插件扩展契约](contracts/plugin-extension-runtime.md)、R8 的[主动运行时契约](contracts/proactive-runtime.md)、后续的[安全 Job 检视契约](contracts/read-only-local-proactive-job-inspection.md)和 R9 的[生产切换契约](contracts/production-cutover.md)共同固定默认关闭、无真实数据/网络/部署边界。R9 只达到“离线生产就绪”，真实生产切换仍需独立执行授权。

R10 的[版本化 Prompt 编排、Persona 与预算契约](contracts/prompt-orchestration.md)已完成并验证。它以 Python 的 Prompt
Block、Persona、时间和裁剪语义为基准，但不执行 Skill、真实 Tool 或真实渠道。
R10 Provider 失败与恢复的分层边界见[Provider 协议、失败语义与思考数据对齐计划](plans/2026-07-19-r10-provider-protocol-alignment-plan.md)；P3 仍只使用 Fake Model 和本地 Session，真实 Provider/网络继续冻结。
R11 已完成 B1 的[Tool Catalog 与 Capability 治理契约](contracts/tool-catalog-capability-governance.md)和 B2a 的[本地审批收件箱契约](contracts/tool-approval-inbox.md)、[ADR-0017](adr/0017-isolate-durable-approval-inbox.md)、[设计](specs/2026-07-18-r11-approval-inbox-design.md)及[实施计划](plans/2026-07-18-r11-approval-inbox-implementation.md)。B2b 已实现无执行的[Pending Operation 契约](contracts/pending-tool-operation.md)、[ADR-0018](adr/0018-use-single-transaction-pending-operation-store.md)、[设计](specs/2026-07-18-r11-pending-operation-design.md)及[实施计划](plans/2026-07-18-r11-pending-operation-implementation.md)的 O1–O5；[Session Anchor/Recovery Capability 契约](contracts/pending-operation-recovery-capability.md)、[ADR-0019](adr/0019-freeze-pending-operation-session-anchor-before-resume.md)及[实施计划](plans/2026-07-19-r11-pending-recovery-capability-implementation.md)的 A1–A7 已完成并通过三套完整 Reactor 门禁。B3 的[只读 Workspace Tools 契约](contracts/read-only-workspace-tools.md)、[ADR-0022](adr/0022-use-explicit-root-for-read-only-workspace-tools.md)、[设计](specs/2026-07-19-r11-read-only-workspace-tools-design.md)与[实施计划](plans/2026-07-19-r11-read-only-workspace-tools-implementation.md)也已完成并通过三套门禁。B4 的[当前会话只读证据 Tool](contracts/read-only-conversation-evidence-tools.md)、[ADR-0025](adr/0025-bind-conversation-evidence-tools-to-current-turn-context.md)、[设计](specs/2026-07-19-r11-conversation-evidence-tools-design.md)与[实施计划](plans/2026-07-19-r11-conversation-evidence-tools-implementation.md)已完成并通过三套阶段门禁。Catalog 只在一个 Turn 内减少 Schema 暴露；Inbox/Reservation 都不提供恢复或副作用执行权限。
R12-S1 的[只读 Skill Catalog](contracts/read-only-skill-catalog.md)已实现并验证：仅显式 `READ_ONLY` Root、无路径 Prompt 投影、依赖存在性检查与 always Skill 注入；默认不扫描任何 Root，且不执行 Skill/脚本。R12-S4 的[按需只读 Skill 正文 Tool](contracts/read-only-skill-content-tool.md)已补齐按名、无路径正文读取：只有可用候选、严格参数、当前 Turn `tool_search` 和 Tool Result 预算同时满足时才返回正文；脚本/网络和动态下载仍需新的 Contract。
R12-S2 的[MCP Resources / Prompts 只读目录](contracts/mcp-read-only-asset-catalog.md)已实现并验证：默认
`DISABLED`，只有显式 `CATALOG_ONLY` 才从受控 stdio Server 发现有界元数据；13 Case Fixture、本地 Reference
Server 与三套完整门禁均通过。它不读取 Resource/Prompt 正文、不向模型注入远端文本，也不新增网络或 Tool。
R12-S3 的[Plugin 生命周期只读映射](contracts/plugin-lifecycle-read-only-mapping.md)已实现并验证：API v2
`LIFECYCLE_TAP` 只发送固定 Phase、安全 Hash、Outcome 与可选稳定码；API v1 `turn.tap`/`tool.tap`/`proactive.tap`
字节语义保持不变。外部 stdio Plugin 需要显式 `api-version: 2`，默认仍零发现、零进程、零网络。
R12-S5 的[当前 Scope 只读记忆召回 Tool](contracts/read-only-memory-recall-tool.md)已实现：只有
`JAVA_NATIVE`、全局 `READ_ONLY` 与 `CURRENT_SCOPE_READ_ONLY` 同时成立时，`recall_memory` 才经当前 Turn `tool_search`
作为 Deferred Tool 出现。它复用 Java 的 cosine/Hotness 稳定排序、先做类型过滤、限制候选/正文/Tool Result 预算，并把
Embedding、Store、取消和内部失败收敛为稳定安全码；它不是 Python `memory2`、Keyword/RRF、时间线或写入 Tool 的对齐声明。
R14-P0 的[主动、自动记忆与 Peer 边界 Contract](contracts/r14-proactive-peer-automation-boundaries.md)已作为下一阶段的
离线前置完成：它只定义 Fixture/Kernel 值，不启动 R8 Scheduler，不接入 Source、Transport、Memory DML 或 Peer Process；
P2–P5 与 R11-B2c 的副作用 Capability 继续按独立 Contract 推进。
完整后续顺序见[Java / Akashic Agent 全量对齐计划](plans/2026-07-18-java-parity-program.md)。

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
- `已冻结`：语义、边界和验收顺序已确定，可以按计划实现；不表示能力已上线。
- `实施中`：已经开始落地，完成情况以任务清单为准。
- `已实现并验证`：实现和规定门禁均通过。
- `已废弃`：不得继续作为实施依据，并必须指向替代文档。

已经完成的 Spec 和 Plan 保留为决策与验证历史，不把它们改写成 Roadmap。Roadmap 只维护阶段级事实；具体行为仍以 Contract、Spec 和 ADR 为准。
