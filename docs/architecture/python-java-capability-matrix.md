# Python/Java 能力差距矩阵

- 状态：当前事实
- 最近更新：2026-07-13
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
| 配置加载 | `agent/config.py`、`agent/config_models.py`、`config.example.toml` | `agent-bootstrap/.../config`、环境变量 | 部分 | 缺 `config.toml` 字段映射、未知字段保留、动态配置 | 中 |
| 启动与装配 | `bootstrap/app.py`、`bootstrap/wiring.py` | `agent-bootstrap` | 部分 | 当前只装配 HTTP 被动聊天 | 低 |
| 入站 HTTP | Dashboard/Bootstrap API | `ChatController` | 完成 | 当前只支持同步 JSON API | 低 |
| 消息总线 | `bus/` | 无 | 未开始 | 需定义版本化入站、出站和生命周期事件 | 中 |
| 被动轮次编排 | `agent/core/passive_turn.py`、`agent/turns/orchestrator.py` | `ChatService`、`SafeChatUseCase` | 部分 | 核心请求—模型—提交闭环完成；生命周期阶段未对齐 | 中 |
| 历史选择 | `agent/policies/history_route.py`、被动支持代码 | `ConversationHistorySelector` | 部分 | 普通 user/assistant 文本投影已有 Golden；缺 Tool、Proactive 与 Context Frame | 中 |
| Prompt 组装 | `agent/prompting/`、`agent/persona.py` | `PromptAssembler` | 部分 | 系统—历史—当前用户投影已有 Golden；缺 Block、Persona、预算 | 中 |
| 模型调用 | `agent/provider.py` | `adapter-spring-ai` | 部分 | OpenAI-compatible 非流式完成；缺流式、工具调用映射、多 Provider 策略 | 低 |
| 失败语义 | `agent/core/runtime_support.py`、错误上下文 | `SafeChatUseCase`、HTTP 异常映射 | 部分 | MVP 失败已隔离；需跨渠道、Tool Loop 错误契约 | 低 |
| 会话内并发 | Python Chat Lane/队列 | `KeyedSessionExecutionGate` | 部分 | 单 JVM 同会话串行；缺跨进程租约与取消 | 中 |
| 请求可观察性 | Python diagnostic/strategy trace | `SafeStructuredLogger`、Observed Ports | 部分 | 结构化安全日志完成；缺统一 Trace 与指标后端 | 低 |
| 健康检查 | Bootstrap 状态 | Actuator + `SqliteHealthIndicator` | 部分 | 当前只覆盖应用与 SQLite | 低 |

## 数据、上下文与记忆

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| SQLite 会话 Schema | Python Session 存储实现、现有 `sessions.db` | `SqliteSchemaInitializer` | 完成 | 核心表兼容；未来字段必须继续增量校验 | 高 |
| 会话读取/轮次提交 | Python 会话仓储 | `JdbcSessionRepository` | 完成 | MVP 原子提交与恢复完成；需持续维护 Python 夹具 | 高 |
| Markdown 记忆 | `agent/memory.py`、`_handbook/memory-markdown.md` | 无 | 未开始 | 先只读解析与 Golden，再开放写入 | 极高 |
| 检索管线 | `agent/retrieval/` | 无 | 未开始 | 定义 Query/Result Port、排序与预算契约 | 中 |
| 上下文预算 | `agent/prompting/budget.py` | 字符/消息上限 | 部分 | 缺 Token 估算、Block 优先级和压缩策略 | 中 |
| Persona/身份 | `agent/persona.py` | 固定 System Prompt | 部分 | 缺工作区 Persona 加载与兼容规则 | 中 |

## 工具与扩展

| 能力 | Python 基准位置 | Java 位置 | 状态 | 主要差距/下一步 | 数据风险 |
| --- | --- | --- | --- | --- | --- |
| Tool 协议与注册 | `agent/tools/base.py`、`registry.py` | 无 | 未开始 | R3 先冻结 Tool/Result/Approval Contract | 中 |
| Tool Loop | `agent/looping/`、`agent/tool_runtime.py` | 无 | 未开始 | 最大轮数、超时、失败隔离、提交语义 | 高 |
| 文件/Shell/Web 工具 | `agent/tools/` | 无 | 未开始 | 先迁移只读工具；副作用工具需审批和沙箱 | 极高 |
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
| Python SQLite 兼容夹具 | 已覆盖核心 Schema、Python 行与 Java 追加游标 | 增加真实版本样本、未知字段和升级路径 |
| 跨语言 Golden | 已建立格式、Manifest、生成器、历史、Prompt、SQLite、错误映射与 CI | 随 R3+ 增加 Tool、Memory 与流式事件 |
| 真实模型 Smoke | Profile 已有，默认不执行 | 需要人工凭证、费用授权和稳定断言 |
| 真实工作区演练 | 未执行 | 只能在备份副本上先做只读差异，再做受控写入 |

## 当前优先级

1. 补齐 Python 配置到 Java 配置的映射和版本化消息 Contract。
2. 完成 R2 的剩余被动聊天能力对齐设计，再进入 R3 Tool Loop。
3. 记忆、渠道、插件和主动能力按 Roadmap 顺序推进，不并行改写真实数据协议。
