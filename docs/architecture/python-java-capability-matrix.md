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
| 启动与装配 | `bootstrap/app.py`、`bootstrap/wiring.py` | `agent-bootstrap` | 部分 | 已装配配置兼容、HTTP 被动聊天和只读最小 Tool Loop；消息总线、渠道和后台能力待迁移 | 低 |
| 入站 HTTP | Dashboard/Bootstrap API | `ChatController` | 完成 | 当前只支持同步 JSON API | 低 |
| 消息总线 | `bus/` | 无 | 未开始 | 需定义版本化入站、出站和生命周期事件 | 中 |
| 被动轮次编排 | `agent/core/passive_turn.py`、`agent/turns/orchestrator.py` | `ChatService`、`ToolLoop`、`SafeChatUseCase` | 部分 | 请求—模型—只读工具—提交闭环与安全生命周期完成；完整 Python 事件总线未迁移 | 中 |
| 历史选择 | `agent/policies/history_route.py`、被动支持代码 | `ConversationHistorySelector`、`ContextAssembler` | 部分 | 普通 user/assistant 与临时 Context Frame 顺序已有 Golden；缺 Proactive 与完整历史路由 | 中 |
| Prompt 组装 | `agent/prompting/`、`agent/persona.py` | `ContextAssembler` | 部分 | 基础 Prompt、Self、长期记忆、近期语境和检索块共同投影已有 Golden；缺完整 Block、Persona、Token 预算 | 中 |
| 模型调用 | `agent/provider.py` | `adapter-spring-ai` | 部分 | OpenAI-compatible 非流式文本与 Tool Call 映射完成；Provider Options 运行时类型和模型配置可保留；缺流式和多 Provider 策略 | 低 |
| 失败语义 | `agent/core/runtime_support.py`、错误上下文 | `ToolLoop`、`SafeChatUseCase`、HTTP 异常映射 | 部分 | 模型、只读工具、迭代上限和提交失败已隔离；需跨渠道与副作用工具错误契约 | 低 |
| 会话内并发 | Python Chat Lane/队列 | `KeyedSessionExecutionGate`、`TurnCancellation` | 部分 | 单 JVM 同会话串行和 Application 取消协议已完成；缺 HTTP 断连传播与跨进程租约 | 中 |
| 请求可观察性 | Python diagnostic/strategy trace | `SafeStructuredLogger`、Observed Ports | 部分 | 结构化安全日志完成；缺统一 Trace 与指标后端 | 低 |
| 健康检查 | Bootstrap 状态 | Actuator + `SqliteHealthIndicator` | 部分 | 当前只覆盖应用与 SQLite | 低 |

## 数据、上下文与记忆

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| SQLite 会话 Schema | Python Session 存储实现、现有 `sessions.db` | `SqliteSchemaInitializer` | 完成 | 核心表兼容；未来字段必须继续增量校验 | 高 |
| 会话读取/轮次提交 | Python 会话仓储 | `JdbcSessionRepository` | 完成 | MVP 原子提交与恢复完成；需持续维护 Python 夹具 | 高 |
| Markdown 记忆 | `agent/memory.py`、`core/memory/markdown.py` | `adapter-workspace`、`MemoryContextService` | 部分 | 三个固定 Profile 的严格 UTF-8 只读投影、上限和零写入已实现；真实 Workspace 与任何写回仍冻结 | 极高 |
| Java 原生语义记忆 | Python `memory2/` 仅作历史参考，旧数据不迁移 | 待实现 | J1 完成 | Java Contract Fixture 已固定 Schema、Float32、Hash、HTTP、排序与 Injection；下一步实现 Kernel 协议 | 高 |
| 检索管线 | `agent/retrieval/` | `MemoryRetrievalPort`、`MemoryContextService` | 部分 | 请求/结果/安全 Trace、Fake 注入闭环和生产 NoOp 已实现；待接入 Java 原生 Store、Embedding、排序与预算 | 中 |
| 上下文预算 | `agent/prompting/budget.py` | 字符/消息上限 | 部分 | 缺 Token 估算、Block 优先级和压缩策略 | 中 |
| Persona/身份 | `agent/persona.py` | 固定 System Prompt | 部分 | 缺工作区 Persona 加载与兼容规则 | 中 |

## 工具与扩展

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| Tool 协议与注册 | `agent/tools/base.py`、`registry.py` | `agent-kernel`、`ToolRegistry` | 部分 | Approval/副作用/幂等协议、整批门禁和生产 Deny All Framework 已实现；缺真实 Approval Channel、Durable Ledger 和具体 Side Effect Tool Contract | 中 |
| Tool Loop | `agent/looping/`、`agent/tool_runtime.py` | `ToolLoop`、`ChatService`、`SideEffectBatchCoordinator` | 部分 | 有界顺序执行、安全预算、审批生命周期、一次性消费、幂等/UNKNOWN 和提交边界已完成；生产仍无可执行副作用，渠道取消未覆盖 | 高 |
| 文件/Shell/Web 工具 | `agent/tools/` | `CurrentTimeTool`（仅时间） | 部分 | 仅完成无副作用时间工具；R3.2 批准不授权真实副作用，仍需逐工具 Capability Contract | 极高 |
| Tool Hook | `agent/tool_hooks/` | 无 | 未开始 | 定义顺序、异常和可变性边界 | 高 |
| Tool Bundle/Search | `agent/tool_bundles.py`、`tool_search.py` | 无 | 未开始 | 在基础 Tool Loop 稳定后迁移 | 中 |
| MCP | `agent/mcp/`、`bootstrap/toolsets/mcp.py` | 无 | 未开始 | 连接生命周期、发现、重连和名称冲突 | 高 |
| Skills | `agent/skills.py` | 无 | 未开始 | 明确技能文件格式及 Java/进程外执行边界 | 中 |
| Plugins | `agent/plugins/` | 无 | 未开始 | Java SPI + Python 进程外 Bridge；不承诺运行时猴子补丁 | 高 |

## 渠道、控制面与后台能力

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| CLI/渠道宿主 | `bootstrap/channel_host.py`、`bootstrap/channels.py` | 无 | 未开始 | R6 先 CLI 后真实渠道 | 中 |
| Telegram 等渠道 | 渠道模块与 Bootstrap | 无 | 未开始 | 需消息 ID、重试、乱序和幂等契约 | 高 |
| 流式输出 | Bus/Channel 生命周期事件 | 无 | 未开始 | 定义 Delta、完成、取消和断线语义 | 中 |
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
| 跨语言 Golden | 已建立格式、Manifest、生成器、历史、Prompt、只读 Context/Memory、SQLite、错误映射、Tool Loop、Runtime 安全与 Approval/Side Effect 场景及 CI | 随后增加语义检索、Memory 写回与流式事件 |
| 真实模型 Smoke | Profile 已有，默认不执行；DeepSeek `deepseek-v4-flash` Tool Smoke 已于 2026-07-14 通过 | 其他 Provider/模型仍需逐组合授权验证；通过不自动启用部署 |
| 真实工作区演练 | 未执行 | 只能在备份副本上先做只读差异，再做受控写入 |

## 当前优先级

1. 按 TDD 完成 R4.2 Task J2 Kernel Memory 与 Embedding 协议；生产继续保持 `AGENT_MEMORY_MODE=DISABLED`。
2. 获批后按 TDD 实现写入、查看、物理删除和语义召回的最小闭环；不实现自动提取/Optimizer，也不执行真实 Workspace 或真实 Embedding Smoke。
3. Approval Channel、Durable Ledger 和真实副作用工具保持冻结，等重写主线进入相应阶段再恢复。
4. 为计划启用 `READ_ONLY` 的每个 Provider/模型组合执行经授权的真实 Tool Smoke；未通过时保持 `DISABLED`。
5. MCP、渠道、插件和主动能力按 Roadmap 顺序推进，不并行改写真实数据协议。
