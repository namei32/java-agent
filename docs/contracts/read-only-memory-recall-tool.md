# R12-S5 当前 Scope 只读记忆召回 Tool 契约

- 阶段：R12-S5
- 状态：已实现；默认 `DISABLED`
- 契约版本：1
- 日期：2026-07-19
- Python 证据：`agent/tools/recall_memory.py`、`plugins/default_memory/engine.py`（基线 `b65a543`）
- 前置：[Java 原生语义记忆契约](semantic-memory-persistence-optimizer.md)
- 拟议 ADR：[ADR-0026](../adr/0026-restrict-memory-recall-to-current-scope-read-only-tool.md)

> 用户已单独批准本契约和 ADR-0026 的受限实现。它只授权本地、默认关闭、Fake Embedding/临时 SQLite 测试范围；**不
> 授权真实 Provider、真实用户数据、生产部署、Python `memory2.db`、跨 Scope 或任何写入能力**。

## 1. Python 差异与目标

Python `recall_memory` 允许 Engine 选择 Schema，并支持 HyDE、关键词/RRF、`answer`/`timeline` intent、类型和时间
过滤、`source_ref`/evidence/trace 回传及跨 Channel Scope。Java 当前只有 Java-native `agent-memory.db` 的当前 Session
Scope cosine/Hotness 检索；旧 Python `memory2.db` 已明确不迁移。

因此 Java 不应把同名 Tool 标为逐字节兼容。Java 仅增加默认关闭的 `recall_memory` **受限安全替代**：在当前 Chat
Session 的 SHA-256 Scope 内，用既有 Java Embedding、cosine/Hotness、稳定排序查询 Java-native 记忆，回送有界的
记忆正文和 opaque Memory ID。它不访问 Python 数据、其他 Session、Route/Channel、evidence/source-ref、检索 trace
或内部 Scope。

### 1.1 Scope 绑定的实现边界

`ChatService` 已对任何入站 Session ID 计算 SHA-256 `sessionBinding` 后交给自动 Context Retrieval；因此
`telegram:<chatId>` 这类渠道 ID 可以参与当前 Scope 的检索，原始 ID 不进入 Java-native memory SQLite。

这不同于显式 Memory HTTP 管理 API：它的路径参数和 `MemoryManagementRules.scope(...)` 有意只接受
`[A-Za-z0-9_-]+`，所以不能通过该 API 管理带冒号的 Telegram Session。该限制在本阶段不得放宽，也不能把 HTTP
管理服务当成 Tool Scope Resolver。`recall_memory` 仅由当前 `ChatService` Turn 的私有
`sessionBinding` 构造查询；模型参数、Tool Runtime、Plugin、MCP、日志和 Tool Result 均不得传递或重算原始
Session ID。

## 2. 启用前提与 Schema

只有下列条件同时满足时才以 Deferred Tool 登记：

1. `agent.memory.mode=JAVA_NATIVE`；
2. `agent.tools.mode=READ_ONLY`；
3. 新增严格 `agent.memory-recall.mode=CURRENT_SCOPE_READ_ONLY`；
4. 当前 Turn 先通过 `tool_search` 解锁 `recall_memory`。

任一条件不满足时零 Tool 注册；`DISABLED` 时 Recall Tool 不获取 Store/Embedding、也不创建后台任务或网络连接（已启用的
Java Native Context Retrieval 仍由其独立配置决定）。未知/小写 Mode、
非 `READ_ONLY` Tool Mode、非 `JAVA_NATIVE` Memory Mode，或 Tool 专用预算放大既有全局 Tool Budget 时必须 Fail Closed。

V1 固定 Schema：

```json
{"query":"缓存策略","memory_type":"FACT","limit":8}
```

- `query`：Strip 后 `1..256` Unicode code points；不接受空白、对象、数组或未知字段。
- `memory_type`：可选，严格大写 Java `NOTE`、`FACT`、`PREFERENCE`、`PROCEDURE`、`EVENT`；不复用 Python 的
  小写 `memory_kind`，避免掩盖类型模型差异。
- `limit`：可选整数 `1..20`，默认 `8`；不接受浮点或字符串数值。
- V1 不支持 `intent`、`time_filter`、调用者 Scope、Session/Channel/Chat ID、Embedding 模型、阈值、候选数、
  排序或 trace 参数。

## 3. 读取、结果与预算

查询 Scope 只由 ChatService 在当前 Turn 私有绑定；模型参数、普通 Tool、Plugin、MCP、日志和结果都不获得原始
Session ID 或 SHA-256 Scope。每次调用只可：计数当前 Scope 候选、在既有受限 Candidate 上生成一次查询 Embedding、
执行 cosine/Hotness 搜索并投影结果。没有 DML、强化、访问计数、自动抽取、Optimizer、Ledger、Approval、Session
提交或外部写入。

成功 JSON 只含 `count`、`limit`、`items`，每项只含 opaque `id`、`memory_type`、`content`、四位小数 `score`；总正文
不超过 12,000 Unicode code points，超过即返回稳定不可用码而非截断。结果不含 `scope`、Session、Hash、Embedding、
模型、Hotness、时间戳、revision、source kind、source/evidence、trace、异常或数据库路径。与现有 Tool Runtime 的 Result/
Wire/调用/取消预算共同生效。

空命中返回合法空页。无效参数返回 `MEMORY_RECALL_INVALID_ARGUMENT`；模式不满足、Store/Embedding/候选上限/预算/取消
或任意内部故障返回 `MEMORY_RECALL_UNAVAILABLE`。错误不得区分“跨 Scope”“不存在”“Embedding 故障”或泄露详情。

可选的 `citation_required` 只能作为不可信 Tool Result 提示，不能成为 System Prompt、执行授权或自动生成最终引用；Java
V1 无 Python evidence/source-ref，因此不能假称引用可追溯到原始消息。

## 4. 非目标和验收

V1 不实现 Python `memory2.db`、HyDE、关键词/RRF、时间线、跨 Scope/Channel/用户、Engine 动态 Tool Profile、
`memorize`、`forget_memory`、自动记忆、Optimizer、远程 Vector Store、真实 Embedding Smoke 或部署启用。

Java-owned `tools/read-only-memory-recall-v1` Fixture 已覆盖 Disabled 零 I/O、严格模式/Schema、
Deferred 解锁、当前 Scope 隔离、类型/排序、候选/正文/Result Budget、Embedding/Store/取消安全失败和零 DML。全部
自动化只用临时 Java SQLite、固定向量和 Fake Embedding；不得读取 Python Workspace 或发起网络请求。

实现已通过 Spotless、`clean verify`、`-Pfailure verify` 与 `-Pcompat verify`。这不改变默认关闭，也不授权真实
Embedding、生产数据或部署启用。
