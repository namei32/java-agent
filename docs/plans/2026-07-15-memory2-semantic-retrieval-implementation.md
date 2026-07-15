# Memory2 语义检索实施计划

- 状态：待批准
- 日期：2026-07-15
- 阶段：R4.2
- 当前执行状态：Task M0 已完成；等待 Contract、ADR、Spec 与计划批准
- Contract：[语义记忆、持久化与优化器契约](../contracts/semantic-memory-persistence-optimizer.md)
- Spec：[Memory2 语义检索纵向切片设计](../specs/2026-07-15-memory2-semantic-retrieval-design.md)
- ADR：[ADR-0005：`memory2` 先使用 Python 兼容 JSON 向量与有界全表检索](../adr/0005-use-python-compatible-json-vectors-for-memory2.md)

> 批准前只允许分析、运行只读基线测试和修改文档。批准范围不包含真实 Workspace、真实 Embedding、生产 Memory Writer、Optimizer、Scheduler、记忆 Tool 或部署启用。

## Task M0：Python 行为分析与范围冻结

状态：已完成。

已读取 Python `memory2` Store、Embedder、Retriever、Default Memory Engine、Markdown Snapshot 与 Optimizer 生产实现，并执行：

```bash
.venv/bin/python -m pytest \
  tests/test_memory2_retrieval_baseline.py \
  tests/test_memory2_dedup_baseline.py \
  tests/test_memory2_consolidation_idempotency.py \
  tests/test_memory_optimizer.py \
  tests/test_recall_memory_tool.py -q
```

结果：45 个测试全部通过。确认 R4.2 只迁移 Schema/基础持久化、Embedding、cosine/Hotness/Scope、Injection 与现有 Context 闭环；Keyword/RRF、Rewrite/HyDE、自动摄入、Optimizer 和 Memory Tool 延后。

## Task M1：批准与 Python Golden 基线

状态：待批准。

目标：固定 Python 兼容 Schema、Content Hash、cosine、Hotness、Scope、Top-K 和 Injection 共同投影。

修改：

- `tools/golden/generate.py`
- `testdata/golden/memory2/semantic-retrieval.json`
- `testdata/golden/manifest.json`
- `agent-bootstrap/src/test/.../GoldenManifestTest.java`

本任务只增加兼容验收资产，不人为破坏实现制造 RED。生成两次并确认 Fixture 与 Manifest SHA-256 一致，再运行一次：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Pcompat -pl agent-bootstrap -am \
  -Dtest=GoldenManifestTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`test: 固定 memory2 语义检索基线`。

## Task M2：Kernel Memory2 与 Embedding 协议

状态：待实施。

目标：用纯 JDK 类型冻结 `EmbeddingPort`、`MemoryStorePort`、内部 `MemoryWriterPort`、Memory Item/Search/Write 值对象，以及 `SEMANTIC_READ_ONLY`、`DEGRADED` 状态。

先新增：

- `agent-kernel/src/test/.../Memory2ContractTest.java`
- `agent-kernel/src/test/.../EmbeddingContractTest.java`

有效 RED 必须因目标类型或约束缺失而编译/断言失败。最小实现后使用同一命令 GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-kernel -am \
  -Dtest=Memory2ContractTest,EmbeddingContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审 Kernel 依赖、不可变 Map/List、向量 Defensive Copy、枚举值、非法类型/状态/维度/分数和 Trace 敏感字段。

提交：`feat: 固定 memory2 核心协议`。

## Task M3：SQLite Read-Only Memory2 Adapter

状态：待实施。

目标：读取 Python 兼容数据库，验证必需 Schema，预过滤 Active、类型和 Scope，严格保持零写入与有界候选。

先新增 `JdbcMemory2StoreTest`，RED 覆盖：

- 数据库缺失返回空且不创建目录/文件。
- Python Schema 与未知字段可读。
- Read-Only 查询不创建 WAL/SHM、不更新 reinforcement/updated_at。
- Python Channel/Chat Scope 排除、Java Session Binding 精确匹配、Legacy Global 开关。
- 非法 Schema、损坏 DB、非法 JSON、候选超限使用脱敏异常。
- Connection/Statement/ResultSet 在成功和失败路径释放。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-sqlite -am \
  -Dtest=JdbcMemory2StoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 读取 Python 兼容 memory2 数据`。

## Task M4：SQLite Writer 原语与 Backup Gate

状态：待实施。

目标：实现不接入生产的 Schema 初始化和 Upsert，固定首次写入备份、精确 Hash 去重、强化与事务回滚。

先新增 `JdbcMemory2WriterTest`，RED 覆盖：

- 新数据库创建兼容三表与索引。
- 已有数据库先产生一致性备份，再执行任何 DDL/DML。
- 备份失败零 Schema/零数据变化。
- 相同规范化正文和类型只保留一条并强化。
- 不同类型不去重；Superseded 恢复；情绪权重取最大值；Event 只补空 happened_at。
- 非法向量、JSON、类型和事务故障完整回滚。
- 不创建、更新或删除 `vec_items`。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-sqlite -am \
  -Dtest=JdbcMemory2WriterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 增加 memory2 备份写入原语`。

## Task M5：Cosine、Hotness 与确定性排序

状态：待实施。

目标：在固定 Clock 下实现 Python 共同评分、阈值、Top-K、类型和稳定 Tie-Break，不引入 `sqlite-vec`。

先新增 `Memory2SemanticSearchTest`，RED 覆盖：

- cosine 降序、基础 threshold、类型、Active 和 Top-K。
- `alpha=0` 等于纯 cosine。
- reinforcement、recency、emotional weight 和 half-life 公式。
- semantic threshold 在 Hotness 混合前应用。
- 相同 final 的 semantic/updatedAt/ID 稳定排序。
- DB 向量维度不符、非有限值、零范数安全跳过。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-sqlite -am \
  -Dtest=Memory2SemanticSearchTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 实现 memory2 语义排序`。

## Task M6：Spring AI Embedding Adapter

状态：待实施。

目标：把 Spring AI `EmbeddingModel` 限制在 Adapter 边界，固定批次、Code Point 截断、顺序和向量验证。

先新增 `SpringAiEmbeddingAdapterTest`，使用 Fake `EmbeddingModel` RED 覆盖：

- 空批次零调用。
- Strip 后空输入拒绝。
- 每批最多 10 条并保持输出顺序。
- 2000 Code Point 截断不拆 surrogate pair。
- 输出数量、维度、有限值和零范数错误转换为稳定 `EmbeddingUnavailableException`。
- 异常不包含输入正文、向量或 Provider Message。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-spring-ai -am \
  -Dtest=SpringAiEmbeddingAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 适配 Spring AI Embedding`。

## Task M7：Semantic Retrieval 与 Injection

状态：待实施。

目标：实现 `SemanticMemoryRetrievalAdapter` 和 `MemoryInjectionFormatter`，接入 R4.1 `MemoryRetrievalPort`，保持检索只读与 Context Frame 临时性。

先新增：

- `SemanticMemoryRetrievalAdapterTest`
- `SemanticMemoryRetrievalChatServiceTest`

RED 覆盖：

- 空库不调用 Embedding；当前 User 是唯一 Embedding Query。
- Embedding 故障返回 `DEGRADED` 且聊天继续。
- 数据边界故障在模型/Tool/提交前失败。
- 类型阈值、两类 Section、每类数量、完整行预算、顺序和空 Section。
- `source_ref`、分数、Scope、Extra 和强制 Tool 指令不进入注入块。
- 同一检索只执行一次，Frame 在 Tool Loop 后续调用保留，最终只提交真实 User/Assistant。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=SemanticMemoryRetrievalAdapterTest,SemanticMemoryRetrievalChatServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 接入 memory2 语义检索闭环`。

## Task M8：Bootstrap 默认关闭装配

状态：待实施。

目标：增加配置校验和 `SEMANTIC_READ_ONLY` 条件装配，不产生 Writer/Optimizer/Scheduler Bean。

先扩展 `MemoryConfigurationTest` 与 `ApplicationConfigurationTest`，RED 覆盖：

- 模板和默认值仍为 `DISABLED`。
- `DISABLED`、`READ_ONLY` 保持既有零检索行为。
- `SEMANTIC_READ_ONLY` 只装配 Reader、Embedding 与 Retrieval。
- 维度、Top-K、阈值、Alpha、Half-Life、Candidate 和预算范围校验。
- DB 缺失启动可用，首次聊天返回空且零 Embedding。
- Spring Context 中没有 `MemoryWriterPort`、Optimizer 或 Scheduler。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-bootstrap -am \
  -Dtest=ApplicationConfigurationTest,MemoryConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 装配默认关闭的语义记忆`。

## Task M9：Golden、Failure 与 HTTP 兼容

状态：待实施。

目标：用生产 Java 实现消费 M1 Golden，并补齐数据库、Embedding 和预算故障分类。

本任务主要增加兼容/故障验收，不人为破坏生产代码制造 RED。验证：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Pcompat -pl agent-application,adapter-sqlite,agent-bootstrap -am \
  -Dtest=Memory2SemanticRetrievalGoldenTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Pfailure -pl agent-application,adapter-sqlite,agent-bootstrap -am \
  -Dtest=SemanticMemoryFailureTest,ChatControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

更新 Contract、Spec、Roadmap、能力矩阵、README、Runbook 与准确实现边界。

提交：`test: 验证 memory2 语义检索兼容`。

## Task M10：阶段门禁、自审与提交

状态：待实施。

按顺序执行一次：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
./mvnw --batch-mode --no-transfer-progress -Pcompat verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
```

随后审计：

- Kernel 禁止依赖和 Spring AI/JDBC 类型泄漏。
- 未跟踪 `.env`、真实 DB、WAL/SHM、Backup、Workspace、Memory、日志或 Secret。
- 生产只有 Read-Only Memory2 Reader，没有 Writer、Optimizer、Scheduler、Memory Tool 或 Dashboard 写 API。
- `DISABLED` 和 `READ_ONLY` 模式零数据库/Embedding 行为；模板继续 `DISABLED`。
- Reader 在测试中不产生 WAL/SHM、不改变 DB Hash；Writer 只在临时测试目录出现并始终备份先行。
- Context Frame 不持久化，Memory 不获得 Tool/Approval 权限，HTTP 错误脱敏。
- `git diff --check`、工作树、计划状态和提交历史一致。

不执行真实 Workspace、真实 Embedding 或真实模型 Smoke。

## 完成定义

- M1–M10 全部完成并记录有效 RED/GREEN 与准确测试数。
- `memory2.db` Python 共同 Schema、Hash、读取和排序 Golden 通过。
- 生产 `SEMANTIC_READ_ONLY` 可完成一次只读 Embedding -> Retrieval -> Context Frame 闭环。
- 默认部署没有数据库访问、Embedding 费用、Memory 写入或 Optimizer 后台任务。
- Writer 原语通过备份、事务和去重测试，但没有生产接线。
- Optimizer Contract 已冻结，代码与文档不把它声明为已实现。
