# Akashic Agent Java 重写指南

- 状态：当前架构基线
- 最近更新：2026-07-13
- 来源：由 Python 仓库 `_handbook/java-rewrite-guide.md` 迁移、裁剪并按当前 Java 实现更新

## 1. 指南用途

本文说明“如何重写”以及必须保持的边界；阶段进度见 [Java 重写 Roadmap](../roadmap/java-rewrite-roadmap.md)，逐项差距见 [能力矩阵](python-java-capability-matrix.md)。旧 Python 手册仅作为历史分析和行为线索，后续 Java 决策以本指南、ADR、Contract 和已批准 Spec 为准。

重写成功不是 Java 进程能够回答一句话，而是：

- 对已迁移范围，Java 的外部行为与批准契约一致；
- 既有 SQLite、配置和 Markdown 数据不会被静默破坏；
- 每个能力都可独立验证、启用和回退；
- Agent Loop 的控制权属于项目核心，而不是 Web 框架或模型 SDK；
- 运维人员能够从日志、健康检查和差异报告定位故障。

## 2. 当前基线

### 2.1 Python 运行时概览

Python 的典型被动链路可概括为：

```text
Channel -> Message Bus -> Agent Runtime -> Passive Turn / Tool Loop
        -> Provider / Tool -> Session / Memory -> Outbound Event -> Channel
```

主要能力分布：

- `bootstrap/`：启动、依赖装配、渠道、Dashboard 和主动任务。
- `bus/`：入站、出站与生命周期事件。
- `agent/core/`、`agent/turns/`、`agent/looping/`：轮次和循环控制。
- `agent/provider.py`：模型供应商访问。
- `agent/tools/`、`agent/mcp/`、`agent/plugins/`：工具和扩展。
- `agent/prompting/`、`agent/retrieval/`、`agent/memory.py`：上下文与记忆。
- `agent/background/`、`agent/subagent.py`、`agent/scheduler.py`：后台与主动能力。

Python 是尚未迁移行为的参考实现，不是必须逐行复制的架构模板。动态导入、全局注册表或运行时猴子补丁应转换为显式协议，不能机械翻译。

### 2.2 Java 已有纵向切片

当前 Java 链路是：

```text
HTTP ChatController
  -> SafeChatUseCase
  -> ChatService
  -> SessionRepository.load
  -> ConversationHistorySelector + PromptAssembler
  -> ChatModel.generate
  -> SessionRepository.appendTurn
  -> HTTP ChatResponse
```

它已经支持同步非流式被动聊天、SQLite 会话恢复、OpenAI-compatible 模型和原子轮次提交。它不包含 Python 的 Message Bus、Tool Loop、Memory、MCP、渠道和主动能力。

## 3. 固定技术基线

| 项目 | 选择 | 约束 |
| --- | --- | --- |
| Java | JDK 21 | 禁止 Preview API，Maven Enforcer 固定 `[21,22)` |
| 构建 | Maven Wrapper | 所有文档和 CI 使用 `./mvnw` |
| 应用框架 | Spring Boot 4.1.x | 只负责装配、HTTP、配置与运维能力 |
| 模型适配 | Spring AI 2.0.x | 只存在于边缘适配器，不控制 Agent Loop |
| 持久化 | SQLite JDBC + 显式 SQL | 不使用 JPA，事务边界由仓储明确控制 |
| 测试 | JUnit 5、AssertJ、ArchUnit、Maven Profile | 跨语言行为还需 Golden Test |
| 代码质量 | Spotless、JaCoCo、Enforcer | 阶段门禁集中执行 |

升级 JDK、Spring Boot、Spring AI 或数据库技术必须单独评估，不能与行为迁移混在一个提交中。

## 4. 目标架构

### 4.1 依赖方向

```text
agent-bootstrap
  -> adapter-spring-ai
  -> adapter-sqlite
  -> agent-application
       -> agent-kernel
```

- `agent-kernel` 只包含纯 Java 领域类型、策略和 Port。
- `agent-application` 编排用例、事务意图、失败、取消和并发语义。
- Adapter 将 JDBC、Spring AI、MCP 或渠道 SDK 转换为项目协议。
- `agent-bootstrap` 只进行启动、配置绑定、Bean 装配、HTTP 和健康检查。

核心不得依赖 Spring、JDBC、Reactor、Spring AI 或供应商 SDK。未来新增模块也必须遵守从外向内的依赖方向。

### 4.2 Agent Loop 所有权

模型 SDK 只能执行一次模型调用或协议转换。以下语义必须由 `agent-application` 控制：

- Prompt 组装和预算；
- 工具循环上限与工具选择；
- 审批、权限和副作用策略；
- 重试、超时、取消和失败隔离；
- 会话何时提交、哪些中间结果可见；
- 生命周期事件和可观察字段。

这样才能稳定复现 Python 行为，并在替换 Provider 时保持领域语义不变。

### 4.3 建议的后续模块演进

在五模块足够时继续使用现有结构。只有职责和依赖确实独立时再拆分，例如：

- `adapter-mcp`：MCP 客户端和进程生命周期；
- `adapter-channel-*`：CLI、Telegram 等渠道；
- `adapter-memory-markdown`：Markdown 记忆解析和安全写入；
- `agent-testkit`：只有测试基础设施形成稳定独立职责后，才考虑承载跨语言夹具读取和 Fake；当前 Golden 继续由现有模块直接验证。

新增 Maven 模块前写 ADR，说明为何包级隔离不足。

## 5. 先冻结协议，再迁移实现

高耦合 Python 对象不能直接成为 Java 类设计。先从可观察行为提取稳定协议，至少包括：

- `InboundMessage` / `OutboundMessage`；
- `TurnStarted` / `TurnCommitted` / `TurnFailed`；
- `StreamDeltaReady`；
- `ToolCallStarted` / `ToolCallCompleted`；
- `Tool` / `ToolCall` / `ToolResult` / `ApprovalDecision`；
- `PromptEnvelope` 与预算结果；
- `MemoryQuery` / `MemoryResult`；
- `Session`、消息顺序和提交游标；
- `McpServerConfig`、主动来源和 Drift 运行记录。

每份 Contract 应明确字段、顺序、空值、幂等键、失败、取消、版本和敏感数据处理。只写 Java 接口而没有跨语言样例，不算完成协议冻结。

## 6. 数据兼容策略

### 6.1 SQLite

当前 `sessions` 与 `messages` Schema 以 Python 数据兼容为第一约束：

- 初始化可以创建缺失表，但不能静默修复同名损坏表；
- 完整 user/assistant 轮次必须在同一事务提交；
- 任一写入失败必须回滚会话游标、元数据和消息；
- 恢复时以安全的最大序号计算下一游标，避免覆盖既有消息；
- 未识别字段和附加表不得被删除。

新增 Schema 前必须提供：旧库读取、幂等初始化、约束、失败回滚、升级和降级策略测试。真实工作区第一次写入前，必须停止全部写入者并备份 `sessions.db`、`sessions.db-wal`、`sessions.db-shm`。

### 6.2 Markdown 记忆

Markdown 是用户可读数据，不是可以任意格式化的内部缓存。迁移顺序固定为：

1. 在副本上只读解析；
2. 比较 Python/Java 解析与检索 Golden；
3. 验证未知段落、注释和格式能保留；
4. 实现临时文件、原子替换和备份；
5. 经批准后才允许写真实副本。

禁止用一次完整序列化静默重排用户记忆。

### 6.3 配置

当前 MVP 使用环境变量。迁移 Python `config.toml` 时，要区分：

- Java 必需且已知字段；
- Java 可选字段和默认值；
- 尚未迁移但必须原样保留的未知字段；
- 密钥，只允许来自环境变量或安全 Secret 来源。

任何自动配置迁移都必须支持预览差异和回滚，不能覆盖原文件。

第一阶段已经批准 [Python/Java 配置兼容契约](../contracts/python-java-configuration.md)：保留现有环境变量模式，同时在发现或显式指定 `config.toml` 时启用只读兼容解析。未知和未迁移字段允许存在，Java 不写回配置；动态更新、自动迁移和 Python 默认工作区切换不在第一阶段范围内。

## 7. 行为兼容与 Golden Test

单元测试证明 Java 内部逻辑正确，Golden Test 证明跨语言观察结果一致。当前版本化夹具固定为：

```text
testdata/golden/
  manifest.json
  history/session-history.json
  prompt/message-envelope.json
  sqlite/session-store.json
  errors/http-error-mapping.json
```

每个夹具包含输入、Python 基准输出、可忽略的非确定字段和 Java 实际输出。时间、UUID、模型自由文本等非确定值要注入或规范化，不能用删除所有字段的方式让测试失去意义。

优先固定：

1. 历史裁剪和消息顺序；
2. Prompt Block 顺序与预算；
3. 工具调用轨迹和失败；
4. 会话数据库及回滚；
5. Memory 解析、检索和写回；
6. 流式事件顺序、取消与断线。

完整格式、非确定字段和审批要求见 [Python/Java Golden Test 夹具规范](../contracts/golden-test-fixtures.md)。Golden 更新必须说明是批准的契约变化还是缺陷修复，不能通过批量重录隐藏回归。

## 8. 失败、并发与提交语义

每个用例在实现前回答：

- 失败发生后，哪些状态已经提交？
- 重试是否会重复副作用？
- 同一会话、不同会话和跨进程如何并发？
- 谁拥有超时与取消？
- 恢复后从哪个持久化游标继续？

当前 MVP 采用：模型成功后才原子追加完整轮次；同 JVM 同会话串行、不同会话可并行；等待饱和或中断映射为受控失败。未来 Tool Loop 和主动任务不能默认沿用这些语义，必须在对应 Spec 中扩展。

## 9. 安全与可观察性

日志允许记录请求 ID、哈希化会话标识、结果、耗时、错误类别、模型/数据库阶段；禁止记录 API Key、完整用户消息、模型完整回答、Memory 正文或真实工作区路径。

高风险工具必须具备：明确审批、最小权限、超时、输出上限、路径边界和审计事件。插件、MCP Server、Subagent 和 Peer Agent 都视为不可信边界，不能共享无限权限或未过滤上下文。

健康检查只回答依赖是否可用，不执行真实模型请求或修改数据。真实模型 Smoke 必须显式启用，并提示网络和费用影响。

## 10. 规范实施流程

一次能力迁移遵循以下闭环：

1. 盘点 Python 行为、数据和外部调用，形成能力矩阵条目。
2. 写 Contract/Spec，明确范围、非目标、失败、并发、数据和验收样例。
3. 对跨模块或长期决策写 ADR。
4. 写实施 Plan，拆成可独立提交和聚焦验证的任务。
5. 在功能分支按 Red-Green-Refactor 实现；兼容验收类任务不人为制造 RED。
6. 小任务只做聚焦验证；大阶段集中执行完整 Reactor、失败和兼容 Profile。
7. 更新 Roadmap、矩阵、Contract、Runbook 和验证证据。
8. 自审 Git diff，确认没有密钥、真实工作区或意外生成物，再合并。

详细协作规则见 [Vibe Coding 工作流](../vibe-coding-workflow.md) 和根目录 `AGENTS.md`。

## 11. 切换与回退

迁移期间同一能力只能有一个写入权威。推荐的切换单位是完整入口或完整工作区，而不是对同一轮次做 Python/Java 双写。

生产切换前必须具备：

- 脱敏副本的只读与写入演练；
- 数据、配置和记忆的完整备份；
- 可执行的停机、切换、校验和回退 Runbook；
- Python/Java 差异报告及已批准例外；
- 明确的观察期和回退触发条件。

在能力矩阵、Golden、回退演练和运维批准未完成前，不能宣布 Python 实现退役。

## 12. 当前下一步

当前不应直接开始迁移所有工具。正确顺序是：

1. 按已批准 Contract 实现 Python/Java 配置兼容，并补齐配置 Golden；
2. 设计并批准核心消息、生命周期和 Tool 协议；
3. 实现最小 Tool Loop；
4. 按 Roadmap 依次迁移 Memory、MCP、渠道和后台能力。

这样可以让每一步都可运行、可比较、可回退，而不是形成一个长期不可验证的“大重写”分支。
