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

## 提议中决策

- [ADR-0010：使用独立 Java SQLite 渠道账本与事务 Outbox](0010-use-dedicated-sqlite-channel-ledger.md)

## 状态与编号

状态使用 `提议`、`已接受`、`已取代` 或 `已拒绝`。提议中的 ADR 不能作为生产实现授权。文件名为四位递增编号加英文短标题。修改已接受决策时，优先新增 ADR 并通过“取代/被取代”关系保留历史。
