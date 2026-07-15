# ADR-0005：`memory2` 先使用 Python 兼容 JSON 向量与有界全表检索

- 状态：提议
- 日期：2026-07-15
- 关联 Contract：[语义记忆、持久化与优化器契约](../contracts/semantic-memory-persistence-optimizer.md)
- 关联 Spec：[Memory2 语义检索纵向切片设计](../specs/2026-07-15-memory2-semantic-retrieval-design.md)

## 背景

Python `memory2` 把 Embedding 的完整 JSON Array 保存在 `memory_items.embedding`，可选加载 `sqlite-vec` 并维护 `vec_items` 虚拟表；扩展不存在、初始化失败、维度不匹配或带时间过滤时会回退全表 cosine。

Java R4.2 需要先获得确定、可测试、默认关闭的语义检索能力，同时保持 Python 数据可读、核心模块不依赖原生扩展，并避免在尚未完成真实数据演练前修改 Python 的向量索引。

候选方案：

| 方案 | 优点 | 主要问题 |
| --- | --- | --- |
| 直接引入 `sqlite-vec` Java 原生扩展 | 大数据量 KNN 性能较好 | 增加平台原生加载、发布、安全与维度迁移边界；Java 写入还要同步虚拟表 |
| 新建独立向量数据库 | 可扩展检索和索引能力 | 引入新服务、双写、一致性、备份和运维；超出渐进式重写边界 |
| JSON Embedding + 有界全表 cosine | 与 Python 主表直接兼容；纯 Java；排序可完全确定；无新基础设施 | 检索复杂度为 O(N×D)，不适合无界数据量 |

## 决策

R4.2 选择第三种方案：

- `memory_items.embedding` 继续使用 Python 可读的 JSON Number Array。
- `adapter-sqlite` 只用显式 SQL 读取经过状态、类型与 Scope 预过滤的候选。
- cosine、Hotness 和最终排序由纯 Java 代码计算。
- 不创建、迁移、更新或依赖 `vec_items`；存在该表时保持原样。
- 配置固定 `max-candidates`，超过上限稳定失败，禁止静默截断造成不可解释漏召回。
- 生产只读模式不创建数据库或 Schema；内部 Writer 也不维护 `vec_items`。
- 后续如果真实脱敏数据证明有性能问题，再用同一 Golden 评估 `sqlite-vec`；替换必须由新 ADR 说明平台支持、索引同步、备份和回滚。

## 理由

该方案优先保证数据兼容、确定性和安全边界。Python 自己已经把全表扫描作为正式回退路径，因此 R4.2 迁移的不是临时伪实现，而是已存在且有测试的可观察行为。候选上限把性能风险变成显式运维信号，不会在高负载时静默返回不完整结果。

## 后果

- R4.2 只适合候选数受控的个人 Agent Workspace，不声明支持大规模多租户向量检索。
- 每轮检索需要解析候选 JSON Embedding 并计算 O(N×D) cosine；配置和 Metrics 必须能观察候选数量与耗时，但不能记录正文或向量。
- Java 写入的新 Embedding 对 Python 主表可见；Python 后续独占数据库并重启时，可以按自身逻辑同步 `vec_items`。
- `sqlite-vec`、ANN、独立 Vector Store 和远程检索服务继续冻结。
