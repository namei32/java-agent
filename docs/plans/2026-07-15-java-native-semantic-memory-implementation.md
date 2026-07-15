# Java 原生语义记忆实施计划

- 状态：实施中
- 日期：2026-07-15
- 阶段：R4.2
- 当前执行状态：Task J0、J1 已完成；下一步是 Task J2 Kernel Memory 与 Embedding 协议
- 批准记录：2026-07-15，用户批准新版方案并授权从 Task J1 开始实施
- Contract：[Java 原生语义记忆、持久化与优化器契约](../contracts/semantic-memory-persistence-optimizer.md)
- Spec：[Java 原生语义记忆纵向切片设计](../specs/2026-07-15-java-native-semantic-memory-design.md)
- ADR：[ADR-0005：采用 Java 原生语义记忆库](../adr/0005-use-java-native-semantic-memory-store.md)

> 已批准边界：整个 R4.2 不使用 Python、不读取或删除 `memory2.db`，不访问真实 Workspace，不调用真实 Embedding，不实现自动记忆提取或 Optimizer。

## Task J0：数据决定与方案重写

状态：已完成。

用户已明确旧 Python 记忆可以丢弃。已把原 `memory2` 兼容草案重写为 Java 原生方案：

- 新数据库 `memory/agent-memory.db`。
- Java 版本化 Schema 与 Float32 Vector Codec。
- 显式 Write/List/Delete API，解决新库为空的问题。
- Session 级 Scope、Embedding、cosine、Hotness 与 Context 注入。
- 自动提取和 Optimizer 执行继续延后。
- 不物理删除旧 Python 文件。

本任务是纯文档任务，只运行 `git diff --check` 和 Spotless 文档检查。

提交：`docs: 改为 Java 原生语义记忆方案`。

## Task J1：Java Contract Fixture

状态：已完成。

目标：固定 Java V1 Schema、Float32 编码、HTTP Request/Response、Hash、Scope、排序和 Injection 示例，不依赖 Python。

修改：

- `testdata/golden/memory/java-native-memory.json`
- `testdata/golden/manifest.json`
- `agent-kernel/src/test/java/io/namei/agent/kernel/compat/GoldenManifestTest.java`

本任务只增加人工评审的契约资产，不人为破坏实现制造 RED。验证：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Pcompat -pl agent-kernel -am \
  -Dtest=GoldenManifestTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

验收记录（2026-07-15）：

- Fixture 固定 V1 Schema、Float32 Little-Endian、Scope/Content/Mutation Hash、Memory HTTP Request/Response、Hotness 稳定排序和双区段 Injection。
- Manifest 新增 `java-contract` 来源；该来源必须包含批准后的 ADR/Contract/Spec Evidence，且禁止 `pythonEvidence`。
- 按纯契约资产规则不制造 RED；聚焦验收实际执行 1 个测试，0 Failure、0 Error、0 Skipped。
- 未运行 Python、真实 Workspace、真实数据库或真实 Embedding。

提交：`test: 固定 Java 原生记忆契约`。

## Task J2：Kernel Memory 与 Embedding 协议

状态：待实施。

先新增 `JavaMemoryContractTest` 与 `EmbeddingContractTest`。有效 RED 必须因目标类型或不变量缺失而失败，随后实现：

- `EmbeddingPort` 与不可变向量结果。
- `MemoryStorePort`、`MemoryWritePort`。
- Memory Item/Search/Write/Delete 值对象。
- `MemoryRuntimeMode.JAVA_NATIVE`。
- `MemoryRetrievalStatus.DEGRADED`。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-kernel -am \
  -Dtest=JavaMemoryContractTest,EmbeddingContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审 Defensive Copy、Hash/Scope 敏感字段、枚举、不变量和 Kernel 禁止依赖。

提交：`feat: 固定 Java 原生记忆协议`。

## Task J3：Schema 与 Float32 Vector Codec

状态：待实施。

先新增 `JavaMemorySchemaInitializerTest` 与 `Float32VectorCodecTest`。RED 覆盖：

- 新库创建 V1 三表、约束和索引。
- 重复初始化幂等。
- 未来版本、同名不兼容结构和损坏 DB 稳定失败。
- 升级前一致性备份；备份失败零 DDL/DML。
- Float32 Little-Endian 往返、长度、维度、NaN/Infinity 和零范数。
- 不读取或创建 `memory2.db`。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-sqlite -am \
  -Dtest=JavaMemorySchemaInitializerTest,Float32VectorCodecTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 建立 Java 原生记忆 Schema`。

## Task J4：SQLite Writer、Reader 与 Mutation 幂等

状态：待实施。

先新增 `JdbcJavaMemoryStoreTest`。RED 覆盖：

- 新条目写入和当前 Scope 列表。
- 相同 Scope/类型/正文强化，不创建第二条。
- 不同 Scope 或类型独立。
- 同一 Scope/Request ID/Argument Hash 重试零 Embedding、零重复强化；参数变化返回幂等冲突。
- 写入与 Ledger 同事务回滚。
- 物理删除正文/向量；删除 Request ID 幂等。
- 跨 Scope 查看和删除返回空/`NOT_FOUND`。
- 候选上限、资源释放和 SQL 参数化。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-sqlite -am \
  -Dtest=JdbcJavaMemoryStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 持久化 Java 原生记忆`。

### J4 数据阶段门禁

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress -pl adapter-sqlite -am verify
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
```

记录实际测试数并审计临时数据库、备份、WAL/SHM、事务和工作树。

## Task J5：Spring AI Embedding Adapter

状态：待实施。

先新增 `SpringAiEmbeddingAdapterTest`，使用 Fake `EmbeddingModel` RED 覆盖：

- 空批次零调用、单批最多 10 条、输出顺序。
- 2000 Unicode Code Point 截断不拆 surrogate pair。
- 输出数量、维度、有限值和零范数验证。
- Provider 异常转换为不含输入或 Provider Message 的稳定异常。
- 使用实际 Provider Options，不丢失模型和维度配置。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-spring-ai -am \
  -Dtest=SpringAiEmbeddingAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 适配 Java 记忆 Embedding`。

## Task J6：Memory Write/List/Delete Application Use Case

状态：待实施。

先新增 `MemoryManagementServiceTest`。RED 覆盖：

- Session ID 生成不可逆 Scope Binding。
- 写入先 Embedding、后事务；Embedding 失败零 Store 调用。
- Hash 只压缩空白，不折叠大小写。
- List 不返回 Vector、Hash、Scope 或 Provider 配置。
- Delete Scope 隔离、Request ID 幂等和 `NOT_FOUND`。
- 内容、类型、Request ID、情绪权重和时间校验。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=MemoryManagementServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 增加显式记忆管理用例`。

## Task J7：Memory HTTP API

状态：待实施。

先新增 `MemoryControllerTest`。RED 覆盖：

- PUT 创建/强化及字段校验。
- GET 固定排序、数量上限和公开字段。
- DELETE 使用 `Idempotency-Key`，物理删除及 `NOT_FOUND`。
- 跨 Scope、禁用模式、超限、Embedding 和数据库失败的稳定 HTTP 映射。
- 错误不泄露正文、Session Binding、SQL、路径或 Provider Message。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-bootstrap -am \
  -Dtest=MemoryControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 开放显式记忆管理 API`。

## Task J8：Semantic Search 与 Injection

状态：待实施。

先新增 `SemanticMemorySearchTest` 与 `MemoryInjectionFormatterTest`。RED 覆盖：

- 空 Scope 零 Embedding。
- cosine、基础阈值、Top-K 和稳定 Tie-Break。
- Reinforcement、Recency、Emotional Weight 与 Half-Life。
- 模型/维度不匹配、非法 BLOB、候选超限。
- Session Scope 精确隔离，无 Global Fallback。
- 两类 Section、每类数量、完整行预算和顺序。
- Memory 不能形成强制 Tool 指令或泄露内部字段。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=SemanticMemorySearchTest,MemoryInjectionFormatterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 实现 Java 语义记忆检索`。

## Task J9：Chat 与 Context Frame 闭环

状态：待实施。

先新增 `JavaSemanticMemoryChatServiceTest`。RED 覆盖：

- 当前真实 User 是唯一 Query。
- Query Embedding 故障返回 `DEGRADED`，聊天继续。
- Schema/候选边界故障在模型/Tool/提交前失败。
- 同一 Turn 只检索一次，Frame 在 Tool Loop 后续调用保留。
- Memory Frame 不持久化，最终仍只提交真实 User/Assistant。
- Memory 不改变 Approval、Tool Risk 或 Ledger。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=JavaSemanticMemoryChatServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 接入 Java 语义记忆闭环`。

## Task J10：Bootstrap 配置与默认关闭

状态：待实施。

扩展 `MemoryConfigurationTest` 和 `ApplicationConfigurationTest`。RED 覆盖：

- 默认和模板继续 `DISABLED`。
- `READ_ONLY` 保持 R4.1 行为且不创建 `agent-memory.db`。
- `JAVA_NATIVE` 装配 Schema、Store、Memory API、Embedding 与 Retrieval。
- 非 Loopback 监听拒绝启用 `JAVA_NATIVE`。
- 所有维度、Top-K、阈值、Alpha、Half-Life、候选和预算范围。
- Spring Context 无 Python Bridge、Optimizer、Scheduler、Memory Tool 或 Vector Store。

聚焦 RED/GREEN：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-bootstrap -am \
  -Dtest=ApplicationConfigurationTest,MemoryConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提交：`feat: 装配默认关闭的 Java 语义记忆`。

## Task J11：Contract、Failure 与文档验收

状态：待实施。

使用生产 Java 实现消费 J1 Fixture，不运行 Python：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Pcompat -pl agent-application,adapter-sqlite,agent-bootstrap -am \
  -Dtest=JavaNativeMemoryContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Pfailure -pl agent-application,adapter-sqlite,agent-bootstrap -am \
  -Dtest=JavaNativeMemoryFailureTest,MemoryControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

更新 Contract、Spec、Roadmap、能力矩阵、README、Runbook 与准确测试数。

提交：`test: 验证 Java 原生语义记忆`。

## Task J12：最终门禁与自审

状态：待实施。

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
./mvnw --batch-mode --no-transfer-progress -Pcompat verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
```

最终审计：

- Kernel 禁止依赖、Spring AI/JDBC/HTTP 类型泄漏。
- 未跟踪 `.env`、数据库、备份、WAL/SHM、Workspace、记忆、日志或 Secret。
- R4.2 新增代码和测试没有 Python 命令、Python Memory Fixture 或 `memory2.db` 访问。
- `DISABLED`/`READ_ONLY` 零 Java Memory DB、零 Memory API、零 Embedding。
- `JAVA_NATIVE` 只在 Loopback 可启用，模板仍为 `DISABLED`。
- 物理删除、Mutation 幂等、迁移备份和事务回滚证据完整。
- Memory 不获得 Tool/Approval 权限，Context Frame 不持久化。
- 生产无 Optimizer、Scheduler、自动摄入、Memory Tool 或远程 Vector Store。
- `git diff --check`、工作树、提交历史和文档状态一致。

不运行真实 Workspace、真实 Embedding、真实模型或旧 Python 数据清理。

## 完成定义

- J1–J12 全部完成并记录有效 RED/GREEN 与准确测试数。
- Java 原生记忆可显式写入、列出、物理删除和语义召回。
- 空 Scope 零 Embedding；失败不会污染 Memory 或 Conversation。
- 默认部署没有 Memory 文件访问、Embedding 费用或后台任务。
- R4.2 构建和测试完全不依赖 Python。
- Optimizer 仍只有 Contract，不被描述为已实现。
