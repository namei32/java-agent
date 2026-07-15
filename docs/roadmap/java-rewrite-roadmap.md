# Java 重写 Roadmap

- 状态：实施中
- 最近更新：2026-07-15
- 基准：`/Users/namei/idea/agent/akashic-agent`
- 目标项目：`/Users/namei/idea/agent/java-agent`

## 目标与路线原则

本 Roadmap 的目标不是把 Python 文件逐个翻译为 Java，而是在保持外部行为、用户数据和可回退能力的前提下，逐步替换运行时能力。每个阶段都必须形成一个可独立验证的纵向切片。

固定原则：

1. Python 是未迁移能力的行为基准，Java 是已批准契约的实现。
2. 先冻结契约和 Golden Test，再迁移高耦合能力。
3. 一次只改变一个主要风险维度；不同时更换语言、数据库和部署拓扑。
4. 数据迁移必须先备份、可验证、可回退，禁止双写真实工作区。
5. 每个阶段必须通过默认、失败路径和兼容性门禁，才可声明完成。

## 状态总览

| 阶段 | 名称 | 状态 | 主要结果 |
| --- | --- | --- | --- |
| R0 | 治理与基线 | 部分完成 | 被动聊天、配置和 Tool Golden 已建立；核心 Tool/Lifecycle Contract 已批准 |
| R1 | Java 工程骨架 | 已完成 | JDK 21、Maven、模块化 Reactor、CI/质量门禁 |
| R2 | 被动聊天纵向切片 | MVP 与 Minor 加固已完成，能力对齐未完成 | HTTP 非流式聊天、SQLite、模型适配、失败与并发语义 |
| R3 | Tool Loop | 部分完成 | R3.1 与 R3.2 默认拒绝 Framework 已完成；真实审批、Durable Ledger 与副作用工具尚未实施 |
| R4 | 上下文与记忆 | R4.1、R4.2 已完成 | Java 原生显式记忆管理与语义检索闭环已通过最终门禁；自动写回/Optimizer 仍冻结 |
| R5 | MCP 与外部工具 | R5.1 已完成 | 静态 stdio 只读 Client、工具发现/投影、取消、隔离和进程回收已验收；远程与副作用范围未开始 |
| R6 | 渠道与控制面 | R6.1 已完成 | 版本化 Message Contract Runtime 已验收；CLI、真实流式、真实渠道与 Dashboard 待后续切片 |
| R7 | 插件与扩展兼容 | 未开始 | Plugin Bridge、Hook 与配置兼容 |
| R8 | 主动运行时 | 未开始 | Scheduler、Proactive、Drift、Subagent |
| R9 | 生产切换 | 未开始 | 真实工作区演练、灰度、回退和 Python 退役 |

## R0：治理、契约与跨语言基线

目标是让后续迁移有唯一事实来源，而不是依赖聊天记录或 Python 实现细节。

已交付：

- 根目录 `AGENTS.md`、Vibe Coding 流程、Spec/Plan/Contract/Runbook 结构。
- 模块边界、SQLite 兼容和 Spring AI 边界 ADR。
- 被动聊天 HTTP 契约与 MVP 设计。
- Golden Test 格式、非确定字段规则、人工更新审批和 Manifest Hash 校验。
- Python 历史、Prompt、SQLite 基准与错误迁移契约夹具。
- Java 历史、Prompt、SQLite、错误映射兼容测试及独立 CI Job。
- Python/Java 配置文件定位、字段优先级、旧别名、未知字段和安全差异 Contract。
- Python 配置解析、安全校验 Golden，以及 TomlJ Parser 选型 ADR。
- Java 只读 TOML Resolver、环境变量双模式、Spring Boot 启动装配和无副作用配置检查。
- 核心消息、生命周期与只读 Tool Contract，以及 Python Tool Message/迁移循环 Golden。
- R6.1 的版本化渠道消息 Contract、Java-owned Fixture、严格序号、唯一终态、取消与背压语义。

待交付：

- 随能力迁移继续固化真实 Provider Streaming、渠道 Adapter 和后续扩展能力夹具。
- 为尚未覆盖的插件、主动任务和跨进程生命周期建立版本化 Contract。

退出门禁：下一阶段所依赖的协议都有契约；相同夹具能在 Python 生成基准、在 Java 验证；Golden 变化必须人工批准。

## R1：Java 工程骨架

状态：已完成。

交付物：

- JDK 21、Maven Wrapper、Spring Boot 4.1、Spring AI 2.0。
- `agent-kernel`、`agent-application`、`adapter-workspace`、`adapter-sqlite`、`adapter-spring-ai`、`agent-bootstrap`。
- Maven Enforcer、Spotless、JaCoCo、ArchUnit、默认/`failure`/`compat` Profile。
- 独立 Git 仓库、Secret 与 Workspace 排除规则。

退出证据：六模块 Reactor 可构建；核心模块不依赖 Spring、JDBC 或供应商 SDK。

## R2：被动聊天纵向切片

状态：批准范围内的 HTTP MVP 已完成；完整 Python 被动聊天对齐仍有缺口。

已交付：

- `POST /api/v1/chat` 同步、非流式被动聊天。
- 会话历史选择、系统 Prompt、模型生成和完整轮次原子提交。
- SQLite Schema 兼容检查、失败回滚、同会话单进程串行化。
- OpenAI-compatible 模型配置、请求 ID、错误映射、安全日志和健康检查。

已完成加固：

- 已补齐领域不变量、历史选择边界、并发等待、Schema 幂等/约束、事务回滚、成功日志和健康失败测试。
- 已移除未使用的 Mockito 测试依赖，并清理动态 Agent 与 Deprecated API 告警。

能力对齐缺口：

- Python Message Bus/Chat Lane 入口、CLI/Telegram 入口尚未迁移。
- 流式 Delta、生命周期事件和完整 Prompt 组装尚未迁移。
- 当前 HTTP 契约是 Java MVP 契约，不等同于 Python 全部被动聊天能力。

退出门禁：Minor 清单、默认、`failure`、`compat` 与被动聊天 Golden 基线已完成；真实模型 Smoke 由人工提供凭证后执行；完整 Python 被动能力仍按后续 Contract 分批对齐。

## R3：Tool Loop

状态：R3.1 只读 Tool Runtime 安全加固与 R3.2 默认拒绝 Framework 均已完成并通过阶段门禁；真实 Approval Channel、Durable Ledger 与具体 Side Effect Tool 尚未实施。

已交付：

- Kernel Tool/消息/Lifecycle 协议，以及 Application 自主管理的有界顺序 Tool Loop。
- Tool Golden 的 7 个场景由 Java 生产循环执行，覆盖提交与失败轨迹。
- Spring AI 只映射 Schema 和消息，不执行真实工具。
- 内置只读 `current_time`，循环上限可通过 `AGENT_TOOL_MAX_ITERATIONS` 配置。
- `DISABLED`/`READ_ONLY` 模式、调用预算、纯 JDK Schema 校验、Arguments/Result 边界。
- 公平并发许可、Virtual Thread 执行、共享 Deadline、超时恢复和 Application 取消协议。
- Tool Runtime 安全 Golden 覆盖预算、参数、结果、超时、许可等待、取消、模式和 Adapter 字节边界。
- Spring AI 通过 Provider Options 原运行时类型注入 Tool Callback，真实 HTTP 桩覆盖 Tool Schema、Assistant Tool Call 和 Tool Result 回送。
- 取消发生在工具任务正文启动前以及 Virtual Thread 启动失败时均能释放并发许可。
- DeepSeek `deepseek-v4-flash` 真实 Tool Smoke 已覆盖 Tool Call、Java 执行、结果回送、最终文本和 SQLite 提交；部署模式仍保持 `DISABLED`，等待单独启用批准。
- Approval Request/Decision、不可变参数指纹、整批零执行和风险不可降级协议已实现。
- Side Effect Ledger Port、一次性审批消费、幂等重放、崩溃 `UNKNOWN` 语义和安全生命周期已接入 Tool Loop。
- `APPROVAL_REQUIRED` 模式与生产 `DenyAllApprovalPort` 已装配；生产没有副作用 Tool、Approval API、Durable Ledger 或 Fake Bean。
- Approval Golden 固定 Python 风险/Hook 拒绝投影与 Java 安全差异；关键失败路径已进入 `failure` Profile。
- R3.2 最终门禁通过：默认 Reactor 151 个测试、`failure` 46 个测试、`compat` 178 个测试，均零失败；Kernel 依赖、Secret、Workspace、生产 Bean、配置与 Schema 审计通过。

R3.2 实现依据：

- [Tool 审批、副作用、幂等与沙箱安全契约](../contracts/tool-approval-side-effect-safety.md)：一次性审批、整批零执行、幂等 Ledger、未知状态和逐工具沙箱门禁。
- [Tool Approval Framework 设计](../specs/2026-07-14-tool-approval-framework-design.md)：生产 Deny All 的 Application Framework 和测试 Fake。
- [Tool Approval Framework 实施计划](../plans/2026-07-14-tool-approval-framework-implementation.md)：Task A1 至 A9 已完成；不包含真实 Side Effect Tool。

后续范围：

- 分别批准并实现真实 Approval Channel 与生产 Durable Ledger；Framework 本身不等于人类审批产品可用。
- 为首个具体 Side Effect Tool 单独批准 Capability/Sandbox Contract 后，再考虑受控迁移。
- 工具调用和最终回答遵循明确的会话提交语义；半完成轮次不得污染历史。

退出门禁：Python Golden 工具轨迹通过；未批准的副作用不会执行；循环上限、超时和工具异常均有故障注入测试。

## R4：上下文、检索与记忆

前置：Tool Loop 稳定，Prompt 与 Memory Contract 已冻结。

当前状态：R4.1 的 C1 至 C8 已全部完成。Python Golden、Kernel Port、只读 Markdown Adapter、ContextAssembler、Retrieval 注入/提交隔离、默认关闭装配和安全 HTTP 映射均已实现；默认 172、`failure` 50、`compat` 206 个测试以及格式、依赖、Secret/Workspace/生产写入面审计全部通过。

R4.2 当前状态：已完成。用户决定不迁移旧 Python 语义记忆后，于 2026-07-15 批准并完成 Java 原生 `agent-memory.db`、显式 Write/List/Delete API、Embedding、cosine/Hotness/Scope 检索、Chat/Context Frame、默认关闭装配、Java-owned Contract/Failure 验收和 Task J1–J12。最终默认 244 个、`failure` 55 个、`compat` 282 个测试全部通过，Kernel 依赖与 Secret/Workspace/生产 Bean/默认配置/工作树审计通过；全过程未运行 Python、真实 Provider 或真实 Workspace。`JAVA_NATIVE` 只在显式 Loopback 配置下启用，生产默认仍为 `DISABLED`。

范围：

- R4.1 已迁移 `SELF.md`、`MEMORY.md`、`RECENT_CONTEXT.md` 的只读共同投影、字符上限和临时 Frame；生产默认关闭且 Retrieval 为 NoOp。
- R4.2 已批准实现 Java 原生 Schema、显式写入/查看/物理删除、cosine/Hotness、Session Scope、Embedding、字符预算和 Context 注入。
- Keyword/RRF、Query Rewrite/HyDE、自动对话提取、Memory Tool、Consolidation 与 Optimizer 实现继续冻结；Optimizer 只冻结 Java DB Revision、事务和 Undo 边界。
- Java 不读取、迁移或删除旧 `memory2.db`；真实 Workspace 与真实 Embedding 仍需单独批准。

退出门禁：临时 Java Workspace 上写入、查看、删除、检索与 Context 闭环通过；默认模式零文件/零费用；旧 Python 数据零访问；迁移、事务和删除可审计。

R4.1 设计依据：

- [只读上下文与记忆兼容契约](../contracts/read-only-context-memory.md)
- [只读 Context/Memory 纵向切片设计](../specs/2026-07-14-read-only-context-memory-design.md)
- [只读 Context/Memory 实施计划](../plans/2026-07-14-read-only-context-memory-implementation.md)

R4.2 已批准依据：

- [Java 原生语义记忆、持久化与优化器契约](../contracts/semantic-memory-persistence-optimizer.md)
- [ADR-0005：采用 Java 原生语义记忆库](../adr/0005-use-java-native-semantic-memory-store.md)
- [Java 原生语义记忆纵向切片设计](../specs/2026-07-15-java-native-semantic-memory-design.md)
- [Java 原生语义记忆实施计划](../plans/2026-07-15-java-native-semantic-memory-implementation.md)

## R5：MCP 与外部工具

状态：R5.1 已实现并验证。完成默认关闭、静态版本化配置、stdio-only、官方 MCP Java SDK `2.0.0` 隔离、Adapter 自有有界 Transport、分页 `tools/list`、稳定名称、安全 Schema、只读 `tools/call`、Wire Cancellation、Stale、一次有界重连、Chat/SQLite 提交闭环和 Bootstrap Destroy Hook。

R5.1 最终门禁通过：默认 284 个测试（270 单元、14 集成）、`failure` 63 个（62 单元、1 集成）、`compat` 323 个（308 单元、15 集成），均为零失败、零错误、零跳过。Kernel 生产依赖仍为空；SDK/Reactor 只存在于 `adapter-mcp`。Java Reference Server 测试后无存活进程，Secret、运行时文件、生产 Python/Shell/HTTP Transport/动态管理/副作用能力扫描均无命中。

当前生产和模板保持 `AGENT_MCP_MODE=DISABLED`。已完成结果只覆盖仓库内 Java Reference Server，不授权真实 MCP Server、Secret、网络、Streamable HTTP、Resources/Prompts/Sampling、动态 Catalog 或副作用 Tool。

实现依据：

- [MCP 只读客户端与 Tool Runtime 契约](../contracts/mcp-client-tool-runtime.md)
- [ADR-0006：采用官方 MCP Java SDK 并自持有界 stdio Transport](../adr/0006-use-official-mcp-java-sdk.md)
- [MCP 只读客户端纵向切片设计](../specs/2026-07-15-mcp-read-only-client-design.md)
- [MCP 只读客户端纵向切片工作计划](../plans/2026-07-15-mcp-read-only-client-implementation.md)

后续 R5 子阶段如需 Streamable HTTP、认证、真实 Server 或副作用 Tool，必须重新冻结相应网络、身份、审批、Ledger、沙箱和数据 Contract，不能沿用 R5.1 授权。

退出门禁：R5.1 已满足参考 Server 发现/调用 Golden、单 Server 故障隔离以及连接/进程可靠回收；后续 R5 子阶段按各自新 Contract 重新验收。

## R6：渠道、消息总线与控制面

状态：R6.1 已实现并验证；R6 整体仍在进行中。

R6.1 至 R6.6 的建议实施顺序见 [R6 渠道、消息总线与控制面总体工作计划](../plans/2026-07-15-r6-channel-message-control-plane-master-plan.md)。该总体计划当前为草案，后续子阶段仍须分别批准 Contract、Spec 和实施计划。

R6.1 已交付：

- Java-owned 版本 1 Message Fixture，以及纯 JDK `InboundMessage`、`OutboundMessage` 和 Route 值对象。
- 从 `TURN_STARTED` 开始的严格连续序号、唯一终态和并发终态竞争保护。
- `REQUESTED`、`CHANNEL_DISCONNECTED`、`BACKPRESSURE_EXCEEDED`、`SHUTDOWN` 四类取消原因的 First Writer Wins 传播。
- 项目自持的有界出站缓冲、发布 Deadline、断开唤醒和背压取消，不引入无界队列或消息中间件。
- 现有非流式 Chat 成功、取消和失败到安全渠道终态的投影；完成消息携带权威完整文本，错误只暴露稳定码。
- 默认 321 个、`failure` 63 个、`compat` 360 个测试以及格式、依赖、Secret、敏感文件、禁止运行时和孤儿进程审计全部通过。

R6.1 只建立可复用的运行时边界，没有新增启动入口、真实 Delta 来源或渠道部署。下一纵向切片 R6.2 应在不重定义消息格式的前提下先接本地 CLI，再接真实 Provider Streaming，并把断开与背压取消传回同一个 Turn Token。

范围：

- 建立 Java `InboundMessage`、`OutboundMessage` 和生命周期事件总线。
- 迁移 CLI，再迁移 Telegram 等渠道；保持渠道层无业务编排。
- 增加流式响应、取消、背压和乱序保护。
- Dashboard 先复用现有前端契约，再决定是否调整前端。

退出门禁：CLI 与至少一个真实渠道的 Golden 会话通过；流式事件顺序和断线语义明确；Dashboard 核心路径兼容。

## R7：插件与扩展兼容

先定义稳定的 Java 扩展 SPI，再为 Python 插件提供进程外 Bridge。禁止在 JVM 内直接加载不受控 Python 插件，也不承诺无边界地复制动态猴子补丁能力。

退出门禁：插件发现、配置、Hook 顺序、异常隔离和版本不兼容均有契约；代表性插件可运行或有明确替代方案。

## R8：主动运行时、Drift 与 Subagent

依次迁移 Scheduler/Proactive、Drift、后台 Subagent 和 Peer Agent。每项都要明确租约、幂等键、取消、重启恢复、资源上限和审计记录；在这些语义批准前不引入分布式调度基础设施。

退出门禁：重启和重复投递不会产生不可控副作用；后台任务可取消、可追踪；并发与失败注入测试通过。

## R9：生产切换与 Python 退役

步骤：

1. 在脱敏的工作区副本上完成全量只读演练和差异报告。
2. 备份真实数据库、WAL/SHM、配置与 Markdown 记忆。
3. 停止 Python 写入者，使用 Java 做受控写入演练。
4. 进行短期灰度并保留一键回退到备份和 Python 的操作手册。
5. 达到稳定观察期后，才停止维护 Python 主路径。

退出门禁：所有能力矩阵中的必需项达到“完成”或有批准的替代方案；数据校验通过；回退演练成功；运维责任人批准切换。

## Roadmap 变更规则

新增模块、数据库、消息中间件或部署单元，必须先写 ADR。阶段状态只能在相应门禁有可复现证据后更新。具体日期在能力和依赖稳定后安排，不能用日历目标替代退出标准。
