# Python/Java 能力差距矩阵

- 状态：当前事实
- 最近更新：2026-07-19
- Python 基准：`/Users/namei/idea/agent/akashic-agent`

## 状态说明

- **完成**：已在 Java 中实现批准范围，并有自动化验证。
- **部分**：存在可用纵向切片，但尚未覆盖 Python 的完整行为。
- **未开始**：Java 尚无对应运行时能力。
- **替代**：Java 有意采用不同机制，必须由 Contract 或 ADR 说明兼容边界。

“完成”只表示当前已批准契约，不自动表示所有 Python 内部实现逐行等价。

## 基础设施与被动聊天

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| 配置加载 | `agent/config.py`、`agent/config_models.py`、`config.example.toml` | `agent-bootstrap/.../config`、环境变量 | 完成 | 已实现批准范围内的只读 TOML/环境变量双模式、Golden、严格诊断和无副作用检查；Deferred 字段随能力迁移激活 | 中 |
| 启动与装配 | `bootstrap/app.py`、`bootstrap/wiring.py` | `agent-bootstrap` | 部分 | 已装配配置兼容、HTTP 被动聊天、显式 Non-Web 本地 CLI、只读 Tool Loop、默认关闭的静态 MCP/Plugin/Proactive Runtime 和 Servlet `ChannelHost`/Telegram；R10 Prompt 加上 R12-S1 Catalog/always Skill 与 S4 deferred `read_skill`，更多渠道与生产配置待迁移 | 低 |
| 入站 HTTP | Dashboard/Bootstrap API | `ChatController` | 完成 | 当前只支持同步 JSON API | 低 |
| 消息总线 | `bus/` | `agent-kernel/.../channel`、`MessageTurnService`、`BoundedOutboundBuffer`、`LocalCliRunner`、Telegram Adapter/可靠协调器 | 部分 | 版本化入站/出站、严格序号、唯一终态、取消、有界背压、CLI、Telegram 及其持久 Inbox/Outbox/恢复已完成；缺其他渠道和跨节点总线 | 中 |
| 被动轮次编排 | `agent/core/passive_turn.py`、`agent/turns/orchestrator.py` | `ChatService`、`ToolLoop`、`SafeChatUseCase` | 部分 | 请求—模型—只读工具—提交闭环与安全生命周期完成；完整 Python 事件总线未迁移 | 中 |
| 历史选择 | `agent/policies/history_route.py`、被动支持代码 | `ConversationHistorySelector`、`ContextAssembler` | 部分 | 普通 user/assistant 与临时 Context Frame 顺序已有 Golden；缺 Proactive 与完整历史路由 | 中 |
| Prompt 组装 | `agent/prompting/`、`agent/persona.py`、`prompts/agent.py` | `PromptOrchestrator`、`AkashicCorePromptRenderer`、`MemoryContextService`、`SkillPromptService` | 部分 | R10 的版本化 Block、System/Frame 固定位置、classpath Core Persona、时间/会话 envelope、code point 预算和整体裁剪已通过三套门禁；R12-S1 另已接入默认关闭的 Catalog/always Skill 投影，S4 通过 Tool Result 提供按名正文。动态 Persona 与完整 Python 行为规则仍在后续阶段 | 中 |
| 模型调用 | `agent/provider.py` | `adapter-spring-ai` | 部分 | OpenAI-compatible 同步文本、Tool Call 和 SSE 文本流已完成；Provider Options/模型配置保留，空闲超时、损坏流和定向传输取消有本地 HTTP 验收。R10-P0 已迁移安全/上下文失败的脱敏、不可重试稳定分类；P1 提供默认关闭的显式 DeepSeek/DashScope 固定 thinking/effort allowlist；P2 仅在显式 `DEEPSEEK` + `SAFE_LOCAL` 的同一内存 Tool Loop 回放最多 16,384 code points 的 `reasoning_content`/`<think>`，分段 SSE 不向 observer 泄露；P3 提供默认关闭的 Tool 前非流式上下文恢复；P4 从标准 prompt/cache-read 数字投影匿名 Turn 聚合，并只在提交后通知默认 no-op Port。P2b 尚未迁移 Python 的跨 Turn reasoning 持久化和空 `reasoning_content` 占位符；P1/P2 不做 URL 推断或任意 body 转发，P2 不写 Session/SQLite/日志/Channel/HTTP/CLI，P4 不保留 native Usage、内容、身份或 Python Dashboard。分层边界见 [R10 Provider 计划](../plans/2026-07-19-r10-provider-protocol-alignment-plan.md)，真实外部流式 Smoke 仍需授权 | 低 |
| 失败语义 | `agent/core/runtime_support.py`、错误上下文 | `ToolLoop`、`SafeChatUseCase`、`MessageTurnService`、HTTP/CLI/Telegram 映射 | 部分 | 模型、只读工具、迭代/流预算、提交、CLI 和 Telegram 终态/网络错误已映射为稳定安全码；缺副作用工具错误契约 | 低 |
| 会话内并发 | Python Chat Lane/队列 | `KeyedSessionExecutionGate`、`TurnCancellationSource`、`BoundedOutboundBuffer`、`LocalCliRunner`、`TelegramChannelAdapter` | 部分 | 单 JVM 同会话串行、取消 First Writer Wins、有界背压、CLI/Telegram 断开与关闭、Provider 取消已完成；缺跨进程租约 | 中 |
| 请求可观察性 | Python diagnostic/strategy trace | `SafeStructuredLogger`、Observed Ports | 部分 | 结构化安全日志完成；缺统一 Trace 与指标后端 | 低 |
| 健康检查 | Bootstrap 状态 | Actuator + `SqliteHealthIndicator` | 部分 | 当前只覆盖应用与 SQLite | 低 |

## 数据、上下文与记忆

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| SQLite 会话 Schema | Python Session 存储实现、现有 `sessions.db` | `SqliteSchemaInitializer` | 完成 | 核心表兼容；未来字段必须继续增量校验 | 高 |
| 会话读取/轮次提交 | Python 会话仓储 | `JdbcSessionRepository` | 完成 | MVP 原子提交与恢复完成；需持续维护 Python 夹具 | 高 |
| Markdown 记忆 | `agent/memory.py`、`core/memory/markdown.py` | `adapter-workspace`、`MemoryContextService` | 部分 | 三个固定 Profile 的严格 UTF-8 只读投影、上限和零写入已实现；真实 Workspace 与任何写回仍冻结 | 极高 |
| Java 原生语义记忆 | Python `memory2/` 仅作历史参考，旧数据不迁移 | Kernel Memory 协议、`JdbcJavaMemoryStore`、`SpringAiEmbeddingAdapter`、Memory Application/HTTP、Bootstrap、R12-S5 `recall_memory` | 完成 | J1 至 J12 已完成独立 V1 库、显式 Write/List/Delete、Embedding、检索与默认关闭装配；R12-S5 再增加仅当前 Chat Scope 的 Deferred 只读召回替代。自动写回/Optimizer、Python 数据迁移与跨 Scope 仍冻结 | 高 |
| 检索管线 | `agent/retrieval/` | `SemanticMemoryRetrievalAdapter`、`SemanticMemorySearch`、`MemoryInjectionFormatter`、`MemoryContextService` | 部分 | 当前 User 的 Scope/cosine/Hotness/Top-K/字符预算与 Chat Frame 复用已实现；缺 Keyword/RRF、Rewrite/HyDE、跨 Session 身份与 Token 预算 | 中 |
| 上下文预算 | `agent/prompting/budget.py` | `PromptBudget`、`PromptTrimPlan`、`PromptOrchestrator` | 部分 | 已实现 code point/token 估算、固定 Block 优先级和整段裁剪；尚未实现 Python 的动态压缩/缓存或 Provider tokenizer | 中 |
| Persona/身份 | `agent/persona.py`、`prompts/agent.py` | classpath `akashic-core-*.md`、`AkashicCorePromptRenderer` | 部分 | 已有 Java-owned Core Persona 与受限行为规则；工作区动态 Persona、完整 Skill/Tool 路由规则与未批准输入仍不加载 | 中 |

## 工具与扩展

Python Tool 的逐项名称、语义差异、Java Runtime/Tool 区分与依赖顺序见
[Python Tool / Java Capability 逐项差距清单](2026-07-19-python-tool-capability-parity-inventory.md)。下表保留跨领域汇总，
不以“已有 Runtime”替代模型可调用 Tool 的对齐证据。

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| Tool 协议与注册 | `agent/tools/base.py`、`registry.py` | `agent-kernel`、`ToolRegistry`、`ToolCatalog`、`ApprovalInbox` | 部分 | Approval/副作用/幂等协议、整批门禁和生产 Deny All Framework 已实现；R11 B1 提供常驻/Deferred Catalog、Turn-scoped `tool_search`、确定性 CJK/精确检索和 Schema 逐轮投放；B3/B4 提供 Workspace 与当前会话只读 Tool；R12-S5 添加只读、当前 Scope、无写入的 `recall_memory`。B2c 的默认关闭 Scope 软失效 Capability/Resume/Cancel/Status，以及仅创建 Pending 的 `forget_memory` Tool/Chat 生产器均已实现；它仍不提供模型可触发执行、Worker 或真实数据访问。 |
| Tool Loop | `agent/looping/`、`agent/tool_runtime.py` | `ToolLoop`、`ChatService`、`SideEffectBatchCoordinator` | 部分 | 有界顺序执行、安全预算、审批生命周期、一次性消费、幂等/UNKNOWN、提交边界和取消 Token 透传已完成；B4 将不公开的当前 Turn Evidence Scope 显式传递给受信 ContextualTool，普通 Tool、MCP 和 Plugin 仍不可见；Telegram 断开已接入同一取消边界，生产仍无可执行副作用 | 高 |
| 文件/Shell/Web 工具 | `agent/tools/` | `CurrentTimeTool`、`ReadWorkspaceFileTool`、`ListWorkspaceDirectoryTool` | 部分 | R11-B3 完成默认关闭、独立显式 Root 的 `read_file`/`list_dir`：逐段无链接路径、严格 UTF-8、固定最大预算、稳定错误投影、Unicode code-point 排序和 `tool_search` 后的 Deferred Schema；图片、递归、写入/编辑、Shell、Web 与真实 Workspace 仍需逐 Tool Capability Contract | 极高 |
| Tool Hook | `agent/tool_hooks/` | R7 Kernel/Application Plugin Tap（已实现） | 部分 | V1 固定顺序、超时和异常隔离，只读投影；可变 Gate/副作用仍冻结 | 高 |
| Tool Bundle/Search | `agent/tool_bundles.py`、`tool_search.py` | `ToolCatalog`、`ToolCatalogSession`、`ToolRegistry` | 部分 | R11 B1 的 Java-owned Catalog/Fixture 已实现并验证：内置工具可常驻，静态只读 MCP 与 B3 Workspace Tools 可 deferred，并在当前 Turn 搜索后于下一模型请求投放 Schema；完整 Bundle、动态注册、权限策略与真实副作用仍在后续 B 阶段 | 中 |
| MCP | `agent/mcp/`、`bootstrap/toolsets/mcp.py` | `adapter-mcp`、Bootstrap `McpRuntime` 装配 | 部分 | Python 基线只覆盖 `tools/list`/`tools/call`；R5.1 已在 Java 对齐该静态 stdio Tool 主线并加上取消、Stale/单次重连和进程回收。R12-S2 的 Resources/Prompts 元数据目录是默认关闭的 Java-owned 安全扩展，不可计为 Python 对齐；正文读取/注入、Streamable HTTP/OAuth、真实 Server Smoke、动态 Catalog 与副作用能力均须先证明必要性并另立 Contract | 高 |
| Skills | `agent/skills.py`、`agent/core/prompt_block.py` | `agent-kernel/.../skill`、`MarkdownSkillCatalogAdapter`、`SkillPromptService`、`ReadSkillTool`、`SkillProperties` | 部分 | R12-S1/S4 已实现并验证默认关闭的只读 Catalog、Workspace 覆盖、依赖可用性、无路径 Prompt 投影、always 注入与 deferred `read_skill`。ADR-0029 已确认 Python 没有待迁移的 Skill Runner；脚本、动态下载或 Python import 只能通过各自 Tool Capability 启用 | 中 |
| Plugins | `agent/plugins/` | R7 Java SPI + 隔离 stdio Bridge、R12-S3 API v2 `LIFECYCLE_TAP`（已实现） | 部分 | 默认关闭的精确只读 Phase/Hash/Outcome 观察已覆盖；无真实 Python import、无 Tool/Channel 注入、无可变 Gate，不承诺运行时猴子补丁 | 高 |

## 渠道、控制面与后台能力

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| CLI/渠道宿主 | `bootstrap/channel_host.py`、`bootstrap/channels.py` | `agent-bootstrap/.../cli`、`channel`、`telegram` | 部分 | 显式 Non-Web CLI、通用 Host、Telegram 私聊与该渠道的持久恢复已离线验证；多渠道和主动宿主未迁移 | 中 |
| Telegram 等渠道 | 渠道模块与 Bootstrap | `agent-bootstrap/.../channel`、`telegram` | 部分 | 数值 Allowlist、Long Polling、持久 Cursor/Claim、事务 Outbox、Receipt、429 有界重试、`UNKNOWN`、恢复与回退已通过 PR #7 合入，PR #8 完成合并后 CI 稳定性修复；真实 Smoke 待授权 | 高 |
| 流式输出 | Bus/Channel 生命周期事件 | Kernel Channel Contract、`BoundedOutboundBuffer`、`MessageTurnService`、Spring AI Streaming Adapter、`LocalCliRunner`、`TelegramTerminalRenderer` | 部分 | Provider Delta、Tool Loop 预览、权威完成、唯一终态、断开/背压、CLI 实时渲染、Telegram 终态投影与持久投递已完成；其他渠道与真实 Smoke 未覆盖 | 中 |
| Dashboard/控制面 API | `bootstrap/dashboard_api.py` | R6.5 后端能力，加 R13-C1 默认关闭的 `GET /api/v1/control/index`，以及 C2-A 默认关闭的 `GET /api/v1/control/history`：既有 Loopback Session/安全过滤后，后者只列举内存终态的 opaque ref/channel/终态/完成时间 | 部分 | C0 的 20 Case 已由 C1 消费，C2-A 的 22 Case 已由 metadata-only 目录消费；C2-B0 已冻结六项历史详情业务决策和 TDD 任务，但无数据访问。无历史 Session/Message/Memory 浏览、正文详情、删除、优化、前端、远程访问、CLI+Web、同步 Chat 或真实渠道。C2-B1–C2-B6/C3–C5 仍须逐项 Contract 与批准 | 中 |
| Scheduler | `agent/scheduler.py` | R8 SQLite Scheduler；默认关闭的 `list_local_proactive_jobs` 安全检视（已实现） | 部分 | V1 固定 AT/EVERY、租约、恢复、hash-only allowlist 与幂等；检视仅复用活动 Runtime，且不等价 Python `list_schedules`（无渠道/身份/正文/时区/运行次数），创建、取消、cron/时区/集群继续冻结 | 高 |
| Proactive | `agent/core/proactive_*`、`bootstrap/proactive.py` | R8 Proactive Gate/NoOp Planner；R14-P0 Fixture/Kernel 边界、P1 未接线 ReadOnly Decision Runner | 部分 | 默认 NoOp Delivery；P0 固定状态/租约与 `FIXED_LOCAL` Source，P1 再完成 Gate + Fake Source + ReadOnly Drift 的安全 skip/pending/cancel 投影；仍未接线。外部源、真实推送、审批、自动 Memory 与网络仍按 [R14 计划](../plans/2026-07-19-r14-proactive-peer-memory-automation-plan.md)逐项推进 | 极高 |
| Drift | `agent/core/drift_turn.py`、`_handbook/drift-guide.md` | R8 只读 Drift（已实现） | 部分 | 有界安全摘要、预算/取消；Workspace 写入和网络冻结 | 极高 |
| Subagent | `agent/subagent.py`、`agent/background/` | R8 受限 Subagent（已实现） | 部分 | 无 Tool/网络/权限继承，父取消/字符/时间预算已验证；不含 Peer Agent | 极高 |
| 生产切换/退役 | Python 部署与数据目录 | R9 sandbox Cutover Contract、`SandboxCutoverAdapter`、离线 CLI/Runbook | 部分 | 已完成仅新建 sandbox 的备份、Manifest、差异、验证和回退检查点；[R15 计划](../plans/2026-07-19-r15-production-migration-retirement-plan.md)已冻结配置、实例、数据副本、灰度与退役门槛。真实数据、双写、部署、停止 Python 与退役仍需独立人工授权 | 极高 |
| Peer Agent | `agent/peer_agent/` | R14-P0 `LOCAL_FAKE` identity/task/state 值对象；无运行时 | 部分 | [R14 计划](../plans/2026-07-19-r14-proactive-peer-memory-automation-plan.md)已冻结 P0 身份/任务/终态边界；仍须固定进程、资源和远端信任，并以 Fake 验证 | 极高 |

## 兼容性测试差距

| 测试资产 | 当前 Java 状态 | 缺口 |
| --- | --- | --- |
| Java 单元/集成测试 | 已建立默认、`failure`、`compat` Profile | 继续随能力扩展 |
| Python SQLite 兼容夹具 | `sessions.db` 共同 Schema 已覆盖 | 语义记忆改为 Java 原生，不增加 `memory2.db` 兼容夹具 |
| 跨语言/Java Contract Fixture | 已建立 Python/Java Golden；R4.2、R5.1、R6.1–R6.4 另由生产 Java 实现消费 Java-owned Memory、MCP、版本化渠道消息、Provider Streaming/CLI、Telegram 与可靠投递 Fixture，不运行 Python | R6.5 的 48 Case 控制面、R7 Plugin、R8 Proactive 与 13 Case 安全 Job 检视、R9 Cutover、R11-B4 的 26 Case 会话证据，以及 R11-B2c 的 24 Case Pending Recovery 和 13 Case Pending Producer Fixture 已由生产 Contract 或 Golden Manifest 消费；Optimizer 仍冻结 |
| 真实模型 Smoke | Profile 已有，默认不执行；DeepSeek `deepseek-v4-flash` Tool Smoke 已于 2026-07-14 通过 | 其他 Provider/模型仍需逐组合授权验证；通过不自动启用部署 |
| 真实工作区演练 | 未执行 | 只能在备份副本上先做只读差异，再做受控写入 |

## 当前优先级

1. R6.1–R6.5 已合入 `main`，PR #9 与主分支三套 CI 均通过；R7–R9 已完成当前 Java 实现并通过默认、`failure`、`compat` 阶段门禁，R9 只提供离线演练。R9 的忽略规则遗漏已由本地 `main` 的 `2ceb44b` 修复。
2. R10 已完成并通过三套阶段门禁；R11 的 B1 Tool Catalog、B2a Local Approval Inbox、B2b Pending Operation 的无执行状态机、B3 默认关闭的独立 Root `read_file`/`list_dir`、B4 当前 Session 只读 `fetch_messages`/`search_messages`，以及 B2c 默认关闭的 Scope 软失效 Capability/Resume/Cancel/Status 与受控 Pending Producer 已完成并通过三套门禁。B2c 的模型入口只创建本机审批 Pending，不执行、没有 Worker 或真实数据访问。R12-S1 只读 Skill Catalog、S3 API v2 只读 Lifecycle Tap 和 S4 deferred `read_skill` 已分别通过完整三套门禁；ADR-0029 已确认 Python Skill 是指令资产而非待迁移的执行器，S2 MCP Assets 目录是 Java-owned 扩展，不改变 Python MCP 对齐进度。候选语义边界见[选择记录](../plans/2026-07-19-r11-first-side-effect-capability-selection.md)。
3. 不回头迁移已明确丢弃的 Python 语义记忆；自动提取/Optimizer、真实 Workspace 和真实 Embedding 启用继续冻结。
4. R11–R15 的全量对齐顺序、Python 证据和完成标准见[Java / Akashic Agent 全量对齐计划](../plans/2026-07-18-java-parity-program.md)。
5. 真实 Telegram、远程 MCP、真实 Python Plugin、主动外部源、真实 Workspace、部署与 Python 退役不因 R10 实施而获得授权。
