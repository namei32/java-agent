# 架构决策记录

ADR（Architecture Decision Record）记录会长期约束多个模块、协议或里程碑的决策。局部实现细节写入 Spec，不为每个类创建 ADR。

## 已接受决策

- [ADR-0001：采用模块化单体与 Ports and Adapters](0001-modular-monolith-and-ports-adapters.md)
- [ADR-0002：采用 SQLite 兼容优先的渐进迁移](0002-sqlite-compatible-incremental-migration.md)
- [ADR-0003：将 Spring AI 限定在模型适配器边界](0003-spring-ai-at-adapter-boundary.md)
- [ADR-0004：使用 TomlJ 进行只读 TOML 解析](0004-use-tomlj-for-read-only-toml-parsing.md)
- [ADR-0005：采用 Java 原生语义记忆库](0005-use-java-native-semantic-memory-store.md)
- [ADR-0006：采用官方 MCP Java SDK 并自持有界 stdio Transport](0006-use-official-mcp-java-sdk.md)
- [ADR-0007：采用项目自有的有界渠道消息协议](0007-use-project-owned-bounded-channel-message-protocol.md)
- [ADR-0008：使用项目自有同步流式观察协议桥接 Provider](0008-use-project-owned-synchronous-stream-observer.md)
- [ADR-0009：Telegram 首渠道使用 JDK HttpClient 与 Bot API 长轮询](0009-use-jdk-httpclient-for-telegram-long-polling.md)
- [ADR-0010：使用独立 Java SQLite 渠道账本与事务 Outbox](0010-use-dedicated-sqlite-channel-ledger.md)
- [ADR-0011：Loopback 控制面事件采用认证 SSE](0011-use-authenticated-sse-for-loopback-control-events.md)
- [ADR-0012：使用 ServiceLoader 与隔离 stdio Plugin Bridge](0012-use-service-loader-and-isolated-stdio-plugin-bridge.md)
- [ADR-0013：使用本地 SQLite 租约主动运行时](0013-use-local-sqlite-leased-proactive-runtime.md)
- [ADR-0014：Python 退役前必须完成可回退演练](0014-require-rehearsal-before-python-retirement.md)
- [ADR-0015：使用项目拥有的版本化 Prompt Section 模型](0015-use-versioned-prompt-section-model.md)
- [ADR-0016：采用 Turn Scoped Tool Catalog](0016-use-turn-scoped-tool-catalog.md)
- [ADR-0017：审批收件箱使用独立耐久边界](0017-isolate-durable-approval-inbox.md)
- [ADR-0018：待审批操作使用单一隔离事务存储](0018-use-single-transaction-pending-operation-store.md)
- [ADR-0019：在恢复前冻结 Pending Operation 的 Session Anchor](0019-freeze-pending-operation-session-anchor-before-resume.md)
- [ADR-0020：使用项目拥有的只读 Skill Catalog](0020-use-project-owned-read-only-skill-catalog.md)
- [ADR-0021：将 MCP Assets 保持为目录发现而非 Prompt 注入](0021-keep-mcp-assets-as-catalog-only.md)
- [ADR-0022：以独立显式根目录提供只读 Workspace Tools](0022-use-explicit-root-for-read-only-workspace-tools.md)
- [ADR-0023：以 API v2 增加只读 Plugin Lifecycle Tap](0023-add-read-only-plugin-lifecycle-tap-v2.md)
- [ADR-0024：以延迟只读 Tool 读取已审计的 Skill 正文](0024-expose-audited-skill-content-through-a-deferred-read-only-tool.md)
- [ADR-0025：以 Turn 上下文受限方式暴露当前会话证据 Tool](0025-bind-conversation-evidence-tools-to-current-turn-context.md)
- [ADR-0026：将记忆召回限制为当前 Scope 的只读 Deferred Tool](0026-restrict-memory-recall-to-current-scope-read-only-tool.md)
- [ADR-0027：先冻结 R14 主动、记忆与 Peer 自动化边界](0027-freeze-r14-proactive-peer-automation-boundaries.md)
- [ADR-0028：在 Provider Adapter 边界分类安全与上下文拒绝](0028-classify-provider-rejections-at-adapter-boundary.md)
- [ADR-0029：将 Skill 视为指令资产而非执行运行时](0029-treat-skills-as-instructional-assets-not-an-execution-runtime.md)
- [ADR-0030：仅在任何 Tool 执行前恢复模型上下文超限](0030-recover-context-limit-before-any-tool-execution.md)
- [ADR-0031：只观察已提交 Turn 的匿名 Provider 缓存用量](0031-observe-anonymous-provider-cache-usage-per-committed-turn.md)
- [ADR-0032：使用显式、仅文本的受信 Provider Options](0032-use-explicit-text-only-trusted-provider-options.md)
- [ADR-0033：只在单个 Tool Loop 内回放有界 Provider reasoning](0033-replay-bounded-provider-reasoning-only-within-one-tool-loop.md)

## 提议中决策

当前无。

## 状态与编号

状态使用 `提议`、`已接受`、`已取代` 或 `已拒绝`。提议中的 ADR 不能作为生产实现授权。文件名为四位递增编号加英文短标题。修改已接受决策时，优先新增 ADR 并通过“取代/被取代”关系保留历史。
