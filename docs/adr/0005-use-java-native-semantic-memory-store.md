# ADR-0005：采用 Java 原生语义记忆库

- 状态：提议
- 日期：2026-07-15
- 数据迁移决定：用户已明确允许丢弃旧 Python 语义记忆，不迁移或读取 `memory2.db`
- 关联 Contract：[语义记忆、持久化与优化器契约](../contracts/semantic-memory-persistence-optimizer.md)
- 关联 Spec：[Java 原生语义记忆纵向切片设计](../specs/2026-07-15-java-native-semantic-memory-design.md)

## 背景

R4.1 已经固定 Markdown Profile、临时 Context Frame 和 `MemoryRetrievalPort`，但生产 Retrieval 仍为 NoOp。原 R4.2 草案计划兼容 Python `memory2.db`，因此包含 Python Schema、JSON Embedding、Legacy Scope、旧数据备份和跨语言 Golden。

用户已明确决定旧 Python 语义记忆可以丢弃。继续兼容 `memory2.db` 会把已不需要的表结构、Scope 缺陷、索引同步和迁移成本带入 Java 主线，并使 Java 数据模型长期受 Python 内部实现约束。

同时，如果只删除兼容要求却仍只实现 Read-Only Retrieval，新 Java 数据库会始终为空，检索能力在生产中没有实际价值。因此 Java 原生方案必须同时提供受控、显式、可删除的最小记忆写入闭环。

## 决策

R4.2 采用独立 Java 原生语义记忆库：

- 数据库固定为 Java Workspace 下的 `memory/agent-memory.db`。
- 不读取、复制、迁移、修改或删除 Python `memory2.db`；旧文件由用户自行保留或后续清理。
- Java 使用版本化 Schema、显式 SQL、显式事务和独立迁移记录。
- Embedding 使用带模型名和维度元数据的 Float32 BLOB，不保留 Python JSON Vector 兼容。
- 第一版使用有界全表 cosine，不引入 `sqlite-vec`、远程 Vector Store 或新基础设施；候选超过上限稳定失败。
- R4.2 同时实现显式 Memory Write/Delete Use Case；不实现对话后自动提取、后台 Consolidation 或 Optimizer 执行。
- 所有 Memory 内容都属于候选上下文，不能授予 Tool 权限、降低风险或形成审批决定。

该决定仅取代 ADR-0002 中“复用现有数据”的原则在语义记忆数据库上的应用。`sessions.db`、配置、Markdown Profile 和其他既有用户数据仍遵守 ADR-0002 的兼容、备份和渐进迁移要求。

## 为什么仍选择 SQLite 与有界全表检索

候选方案：

| 方案 | 优点 | 主要问题 |
| --- | --- | --- |
| SQLite + Float32 BLOB + 有界全表 cosine | 单进程事务清晰、无新服务、可确定测试、备份简单 | O(N×D)，只适合个人 Agent 的受控数据量 |
| SQLite + `sqlite-vec` | KNN 性能更好 | 增加原生扩展加载、跨平台发布、索引同步与恢复边界 |
| 独立 Vector Store | 可扩展 ANN 与大数据量 | 新服务、双写、一致性、认证、备份和运维超出当前阶段 |

第一版选择 SQLite 全表检索，并用 `max-candidates` 把容量风险变成显式错误。若脱敏性能基准证明不能满足目标，再用同一 Contract Test 评估索引方案；替换需要新 ADR。

## 后果

- Java 重写不再需要 Python 运行时、Python 数据库、Python Golden 生成器或跨语言 Memory Schema 测试。
- R4.2 可以按 Java 领域模型设计类型、Scope、幂等键、删除和迁移，不继承 Python 内部字段。
- 新库从空状态开始；只有显式写入成功的记忆才能被检索。
- 不提供旧记忆迁移和回滚到 Python Memory 的承诺；这不影响保留原 Python Workspace 文件。
- R4.2 不声明支持大规模、多租户或跨渠道身份；当前 Scope 绑定现有 Session，R6 再引入稳定用户/渠道身份。
- 自动记忆提取与 Optimizer 仍需独立批准，不能因为 Writer 已存在而自动启用。
