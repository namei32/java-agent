# Python/Java 能力差距矩阵

- 状态：当前事实
- 最近更新：2026-07-15
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
| 启动与装配 | `bootstrap/app.py`、`bootstrap/wiring.py` | `agent-bootstrap` | 部分 | 已装配配置兼容、HTTP 被动聊天、显式 Non-Web 本地 CLI、只读 Tool Loop 和默认关闭的静态 MCP Runtime；真实 Channel Host 与后台能力待迁移 | 低 |
| 入站 HTTP | Dashboard/Bootstrap API | `ChatController` | 完成 | 当前只支持同步 JSON API | 低 |
| 消息总线 | `bus/` | `agent-kernel/.../channel`、`MessageTurnService`、`BoundedOutboundBuffer`、`LocalCliRunner` | 部分 | 版本化入站/出站、严格序号、唯一终态、取消、安全错误、有界背压和 CLI Adapter 已完成；缺真实外部 Channel Host、持久投递和恢复 | 中 |
| 被动轮次编排 | `agent/core/passive_turn.py`、`agent/turns/orchestrator.py` | `ChatService`、`ToolLoop`、`SafeChatUseCase` | 部分 | 请求—模型—只读工具—提交闭环与安全生命周期完成；完整 Python 事件总线未迁移 | 中 |
| 历史选择 | `agent/policies/history_route.py`、被动支持代码 | `ConversationHistorySelector`、`ContextAssembler` | 部分 | 普通 user/assistant 与临时 Context Frame 顺序已有 Golden；缺 Proactive 与完整历史路由 | 中 |
| Prompt 组装 | `agent/prompting/`、`agent/persona.py` | `ContextAssembler` | 部分 | 基础 Prompt、Self、长期记忆、近期语境和检索块共同投影已有 Golden；缺完整 Block、Persona、Token 预算 | 中 |
| 模型调用 | `agent/provider.py` | `adapter-spring-ai` | 部分 | OpenAI-compatible 同步文本、Tool Call 和 SSE 文本流已完成；Provider Options/模型配置保留，空闲超时、损坏流和定向传输取消有本地 HTTP 验收；缺多 Provider 策略及经授权的真实外部流式 Smoke | 低 |
| 失败语义 | `agent/core/runtime_support.py`、错误上下文 | `ToolLoop`、`SafeChatUseCase`、`MessageTurnService`、HTTP/CLI 映射 | 部分 | 模型、只读工具、迭代/流预算、提交和 CLI 渠道终态已映射为稳定安全错误；缺真实外部渠道与副作用工具错误契约 | 低 |
| 会话内并发 | Python Chat Lane/队列 | `KeyedSessionExecutionGate`、`TurnCancellationSource`、`BoundedOutboundBuffer`、`LocalCliRunner` | 部分 | 单 JVM 同会话串行、取消原因 First Writer Wins、有界背压、CLI 断开/关闭和 Provider 取消已完成；缺真实网络渠道断线与跨进程租约 | 中 |
| 请求可观察性 | Python diagnostic/strategy trace | `SafeStructuredLogger`、Observed Ports | 部分 | 结构化安全日志完成；缺统一 Trace 与指标后端 | 低 |
| 健康检查 | Bootstrap 状态 | Actuator + `SqliteHealthIndicator` | 部分 | 当前只覆盖应用与 SQLite | 低 |

## 数据、上下文与记忆

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| SQLite 会话 Schema | Python Session 存储实现、现有 `sessions.db` | `SqliteSchemaInitializer` | 完成 | 核心表兼容；未来字段必须继续增量校验 | 高 |
| 会话读取/轮次提交 | Python 会话仓储 | `JdbcSessionRepository` | 完成 | MVP 原子提交与恢复完成；需持续维护 Python 夹具 | 高 |
| Markdown 记忆 | `agent/memory.py`、`core/memory/markdown.py` | `adapter-workspace`、`MemoryContextService` | 部分 | 三个固定 Profile 的严格 UTF-8 只读投影、上限和零写入已实现；真实 Workspace 与任何写回仍冻结 | 极高 |
| Java 原生语义记忆 | Python `memory2/` 仅作历史参考，旧数据不迁移 | Kernel Memory 协议、`JdbcJavaMemoryStore`、`SpringAiEmbeddingAdapter`、Memory Application/HTTP、Bootstrap | 完成 | J1 至 J12 已完成：独立 V1 库、显式 Write/List/Delete、幂等、物理删除、Embedding、检索、默认关闭装配和三套最终门禁均已验收；自动写回/Optimizer 属于未来新范围，仍冻结 | 高 |
| 检索管线 | `agent/retrieval/` | `SemanticMemoryRetrievalAdapter`、`SemanticMemorySearch`、`MemoryInjectionFormatter`、`MemoryContextService` | 部分 | 当前 User 的 Scope/cosine/Hotness/Top-K/字符预算与 Chat Frame 复用已实现；缺 Keyword/RRF、Rewrite/HyDE、跨 Session 身份与 Token 预算 | 中 |
| 上下文预算 | `agent/prompting/budget.py` | 字符/消息上限 | 部分 | 缺 Token 估算、Block 优先级和压缩策略 | 中 |
| Persona/身份 | `agent/persona.py` | 固定 System Prompt | 部分 | 缺工作区 Persona 加载与兼容规则 | 中 |

## 工具与扩展

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| Tool 协议与注册 | `agent/tools/base.py`、`registry.py` | `agent-kernel`、`ToolRegistry` | 部分 | Approval/副作用/幂等协议、整批门禁和生产 Deny All Framework 已实现；缺真实 Approval Channel、Durable Ledger 和具体 Side Effect Tool Contract | 中 |
| Tool Loop | `agent/looping/`、`agent/tool_runtime.py` | `ToolLoop`、`ChatService`、`SideEffectBatchCoordinator` | 部分 | 有界顺序执行、安全预算、审批生命周期、一次性消费、幂等/UNKNOWN、提交边界和取消 Token 透传已完成；生产仍无可执行副作用，真实渠道断开入口尚未接线 | 高 |
| 文件/Shell/Web 工具 | `agent/tools/` | `CurrentTimeTool`（仅时间） | 部分 | 仅完成无副作用时间工具；R3.2 批准不授权真实副作用，仍需逐工具 Capability Contract | 极高 |
| Tool Hook | `agent/tool_hooks/` | 无 | 未开始 | 定义顺序、异常和可变性边界 | 高 |
| Tool Bundle/Search | `agent/tool_bundles.py`、`tool_search.py` | 无 | 未开始 | 在基础 Tool Loop 稳定后迁移 | 中 |
| MCP | `agent/mcp/`、`bootstrap/toolsets/mcp.py` | `adapter-mcp`、Bootstrap `McpRuntime` 装配 | 部分 | R5.1 已完成静态 stdio、官方 SDK 隔离、分页发现、稳定命名、安全 Schema、只读调用、Wire Cancellation、Stale/单次重连和进程回收；缺 Streamable HTTP/OAuth、Resources/Prompts、真实 Server Smoke、动态 Catalog 与副作用能力 | 高 |
| Skills | `agent/skills.py` | 无 | 未开始 | 明确技能文件格式及 Java/进程外执行边界 | 中 |
| Plugins | `agent/plugins/` | 无 | 未开始 | Java SPI + Python 进程外 Bridge；不承诺运行时猴子补丁 | 高 |

## 渠道、控制面与后台能力

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| CLI/渠道宿主 | `bootstrap/channel_host.py`、`bootstrap/channels.py` | `agent-bootstrap/.../cli` | 部分 | 显式 Non-Web、本地单 Turn、有界 UTF-8 CLI 已完成；通用 Host/Telegram 文档草案待批准，生产实现尚未开始 | 中 |
| Telegram 等渠道 | 渠道模块与 Bootstrap | 待批准的 `agent-bootstrap/.../channel`、`telegram` 设计 | 草案 | Telegram 已选择；私聊数值 Allowlist、Long Polling、进程内去重和终态合并 Contract 待批准，可靠投递后置 R6.4 | 高 |
| 流式输出 | Bus/Channel 生命周期事件 | Kernel Channel Contract、`BoundedOutboundBuffer`、`MessageTurnService`、Spring AI Streaming Adapter、`LocalCliRunner` | 部分 | Provider Delta、Tool Loop 预览、权威完成快照、唯一终态、断开/背压和 CLI 渲染已完成；缺真实外部渠道投影、持久投递和恢复 | 中 |
| Dashboard API | `bootstrap/dashboard_api.py` | 仅 Actuator/Chat API | 部分 | 保留现有 React/Vite，逐接口兼容 | 中 |
| Scheduler | `agent/scheduler.py` | 无 | 未开始 | 需持久化、重启恢复、时区和幂等 | 高 |
| Proactive | `agent/core/proactive_*`、`bootstrap/proactive.py` | 无 | 未开始 | 需来源、审批、限流和审计 | 极高 |
| Drift | `agent/core/drift_turn.py`、`_handbook/drift-guide.md` | 无 | 未开始 | 需运行记录、预算、取消和回退 | 极高 |
| Subagent | `agent/subagent.py`、`agent/background/` | 无 | 未开始 | 需资源上限、父子生命周期和结果协议 | 极高 |
| Peer Agent | `agent/peer_agent/` | 无 | 未开始 | 需身份、进程和远端信任边界 | 极高 |

## 兼容性测试差距

| 测试资产 | 当前 Java 状态 | 缺口 |
| --- | --- | --- |
| Java 单元/集成测试 | 已建立默认、`failure`、`compat` Profile | 继续随能力扩展 |
| Python SQLite 兼容夹具 | `sessions.db` 共同 Schema 已覆盖 | 语义记忆改为 Java 原生，不增加 `memory2.db` 兼容夹具 |
| 跨语言/Java Contract Fixture | 已建立 Python/Java Golden；R4.2、R5.1、R6.1 与 R6.2 另由生产 Java 实现消费 Java-owned Memory、MCP、版本化渠道消息和 Provider Streaming/CLI Fixture，不运行 Python | 后续随新能力增加 Optimizer 与真实渠道 Adapter Contract |
| 真实模型 Smoke | Profile 已有，默认不执行；DeepSeek `deepseek-v4-flash` Tool Smoke 已于 2026-07-14 通过 | 其他 Provider/模型仍需逐组合授权验证；通过不自动启用部署 |
| 真实工作区演练 | 未执行 | 只能在备份副本上先做只读差异，再做受控写入 |

## 当前优先级

1. R6.1/R6.2 已完成并通过三套离线/远程门禁；Telegram 已选为首渠道，下一主线是批准 R6.3 Channel Host/Telegram Contract、JDK HTTP 长轮询、数值身份和离线实现边界。
2. 不回头迁移已明确丢弃的 Python 语义记忆；自动提取/Optimizer、真实 Workspace 和真实 Embedding 启用继续冻结。
3. Approval Channel、Durable Ledger 和真实副作用工具保持冻结，等重写主线进入相应阶段再恢复。
4. 为计划启用 `READ_ONLY` 的每个 Provider/模型组合执行经授权的真实 Tool Smoke；未通过时保持 `DISABLED`。
5. R5.2 远程 MCP、真实渠道、插件和主动能力仍按 Roadmap/独立 Contract 推进，不并行改写真实数据协议；R5.1/R6.2 的本地授权不能扩张为真实 Server、真实渠道或副作用授权。
