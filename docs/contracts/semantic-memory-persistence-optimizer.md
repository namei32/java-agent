# Java 原生语义记忆、持久化与优化器契约

- 状态：已批准
- 契约版本：2
- 日期：2026-07-15
- 批准记录：2026-07-15，用户批准新版方案并授权从 Task J1 开始实施
- 适用阶段：R4.2 Java 原生语义记忆纵向切片
- 数据迁移决定：旧 Python 语义记忆允许丢弃，不读取或迁移 `memory2.db`
- 关联 ADR：[ADR-0005：采用 Java 原生语义记忆库](../adr/0005-use-java-native-semantic-memory-store.md)

> 本契约授权在 Java 专用或临时测试 Workspace 中实现 `agent-memory.db`、显式记忆写入/查看/删除、Embedding 和语义检索。它不授权访问或删除真实 Python Workspace，不授权自动提取所有对话、运行 Optimizer、开放 Memory Tool、调用真实 Embedding Provider 或把服务暴露到未经认证的远程网络。

## 1. 目标

R4.2 把 R4.1 的生产 NoOp Retrieval 推进为完整、可用且可删除的 Java 原生记忆闭环：

```text
显式写入 -> Embedding -> agent-memory.db
当前 User -> Embedding -> Scope/相似度/Hotness 检索 -> Context Frame -> 模型
显式删除 -> 物理删除正文与向量
```

新库从空状态开始，因此本阶段不能只实现 Reader。必须同时提供受控的显式写入和删除用例；自动记忆提取、后台 Consolidation 与 Optimizer 执行继续延后。

## 2. 已确认与待实现边界

### 2.1 已确认决定

- Java 运行、构建和测试不依赖 Python。
- 不读取、不复制、不迁移、不修改、不自动删除 Python `memory2.db`。
- 旧 Python 语义记忆不进入 Java；Java 首次启用时记忆为空。
- R4.1 的 Markdown Profile Reader 可以继续存在，但 Java 原生语义记忆不写 `SELF.md`、`MEMORY.md` 或 `RECENT_CONTEXT.md`。
- Session、配置和其他既有数据仍遵守原有兼容与备份 Contract；“旧记忆可丢弃”不等于可以删除整个 Python Workspace。

### 2.2 R4.2 实现范围

- Java 原生 SQLite Schema 与版本迁移。
- `MemoryStorePort`、`MemoryWritePort`、`EmbeddingPort` 和应用用例。
- 显式 Memory HTTP API：写入、查看和删除。
- 当前消息的 Embedding、cosine、Hotness、Scope、Top-K 与字符预算。
- 既有 R4.1 Context Frame 注入和 Conversation 提交隔离。
- 默认关闭装配、失败分类、审计和阶段门禁。

### 2.3 明确延后

- 自动从每轮对话提取事实或偏好。
- `remember_memory`、`forget_memory` 等模型可调用 Tool。
- Keyword/RRF、Query Rewrite、HyDE、时间线问答和远程 Vector Store。
- Optimizer 生产实现、Scheduler、后台任务和自动合并。
- 跨 Session、跨 Channel、跨用户的身份映射。
- 真实 Workspace、真实 Embedding 和部署启用。

## 3. 运行模式

`MemoryRuntimeMode` 扩展为：

| 模式 | Markdown Profile | Java 语义库 | Memory API | Retrieval |
| --- | --- | --- | --- | --- |
| `DISABLED` | 空 | 不创建、不访问 | 不可用 | NoOp |
| `READ_ONLY` | R4.1 固定文件只读 | 不创建、不访问 | 不可用 | NoOp |
| `JAVA_NATIVE` | R4.1 固定文件只读 | 可创建、读写 | 显式启用 | 语义检索 |

- 模板、测试默认和部署默认继续为 `DISABLED`。
- `JAVA_NATIVE` 只允许使用 Java 专用 Workspace。
- 当前 HTTP 没有认证；只允许在 Loopback 监听时启用 Memory API。未来远程监听必须先增加认证与授权 Contract。

## 4. Java 原生持久化契约

### 4.1 固定路径与 Schema 所有权

- 数据库固定为 `${workspace}/memory/agent-memory.db`。
- Schema Initializer 只接受文件名 `agent-memory.db`，拒绝被指向 `memory2.db` 或其他文件。
- HTTP、模型、Tool 和用户输入不能提供数据库路径。
- Java 是唯一 Schema Owner 和 Writer；不存在 Python/Java 双写。
- 新库使用 `memory_schema` 记录单调递增版本。发现未来版本、同名不兼容列或损坏数据库时启动失败，不猜测修复。
- Schema 升级前必须通过 SQLite Backup API 生成一致性备份；备份失败时零 DDL/DML。
- 新建空库不需要迁移备份；删除旧 Python 记忆不是 Schema 初始化的一部分。
- R4.2 实现保留仅含合法 `memory_schema` 且 `version=0` 的 Java 内部迁移锚点；它没有生产 Writer。V0 升 V1 必须先备份，其他缺表、额外表、View、Trigger、超大或未来版本均拒绝。

### 4.2 V1 Schema

```sql
CREATE TABLE memory_schema (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  version INTEGER NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE memory_items (
  id TEXT PRIMARY KEY,
  scope_binding TEXT NOT NULL,
  memory_type TEXT NOT NULL,
  content TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  embedding BLOB NOT NULL,
  embedding_model TEXT NOT NULL,
  embedding_dimensions INTEGER NOT NULL,
  reinforcement INTEGER NOT NULL DEFAULT 1,
  emotional_weight INTEGER NOT NULL DEFAULT 0,
  source_kind TEXT NOT NULL,
  happened_at TEXT,
  revision INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(scope_binding, memory_type, content_hash)
);

CREATE INDEX ix_memory_items_scope_updated
  ON memory_items(scope_binding, updated_at DESC, id ASC);

CREATE TABLE memory_mutations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  scope_binding TEXT NOT NULL,
  request_id TEXT NOT NULL,
  operation TEXT NOT NULL,
  argument_hash TEXT NOT NULL,
  item_id TEXT,
  result_status TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(scope_binding, request_id)
);
```

约束：

- `memory_type` 只接受 `NOTE`、`FACT`、`PREFERENCE`、`PROCEDURE`、`EVENT`。
- `content` Strip 后不能为空，最大 4000 Java 字符。
- `scope_binding` 是原始 Session ID UTF-8 字节的 SHA-256 小写十六进制，不存原始 Session ID。
- `content_hash` 是规范化 Content UTF-8 字节的 SHA-256 小写十六进制；唯一键包含 Scope 和类型。
- `embedding` 使用 Little-Endian Float32 BLOB，维度由 `embedding_dimensions` 明确记录。
- `embedding_model` 必须与当前检索配置匹配；模型或维度不同的条目不参与检索，未来由独立 Backfill 处理。
- `reinforcement >= 1`；`emotional_weight` 在 `0..10`。
- 时间使用 UTC ISO-8601；`happened_at` 可为空。
- `argument_hash` 固定 Mutation V1 的全部语义参数，不保存正文、Request ID 或原始 Session ID。
- `memory_mutations` 不保存正文、Embedding、原始 Session ID 或 Provider 响应。

### 4.3 显式写入与幂等

#### 4.3.1 Hash V1

正文先执行与 Java `String.strip()` 等价的首尾去空白，再把每段连续 `Character.isWhitespace` 字符压成一个 ASCII 空格。不得大小写折叠、Unicode Normalization 或语言相关转换。`content_hash` 对该规范化正文的 UTF-8 字节做 SHA-256。

`argument_hash` 使用固定字段序列。每个字段先写 4 Byte Big-Endian 无符号 UTF-8 Byte Length，再写 UTF-8 内容；空字段写零长度。对最终 Byte Sequence 做 SHA-256，并输出小写十六进制：

- 写入：`java-memory-mutation-v1`、`UPSERT`、Memory Type、Content Hash、Emotional Weight 十进制、规范化为 `Instant.toString()` 的 HappenedAt；HappenedAt 为空时写空字段。
- 删除：`java-memory-mutation-v1`、`DELETE`、空字段、Item ID、空字段、空字段。

Scope 已包含在 Ledger 唯一键中，Request ID 是 Ledger Key，因此二者不重复进入 `argument_hash`。固定的显式 API Source Kind 也不进入 Hash。任何类型、正文、情绪权重、HappenedAt、Operation 或 Item ID 变化都必须产生不同 Hash。

#### 4.3.2 写入流程

写入流程：

1. 校验 Session、Request ID、类型、正文、长度和情绪权重。
2. 计算不含正文的 Argument Hash，以 `(scope_binding, request_id)` 检查幂等结果；相同 Hash 返回原结果，不调用 Embedding，不写数据库。
3. 同一 Request ID 对应不同 Argument Hash 时返回 `IDEMPOTENCY_CONFLICT`。
4. 首次请求对规范化正文调用一次 Embedding；失败时不开始数据库事务。
5. 使用单个显式事务再次检查幂等键，插入或强化条目，同时写 `memory_mutations`。
6. 提交后才返回成功。

重复 `(scope_binding, memory_type, content_hash)`：

- 不创建第二条。
- `reinforcement + 1`、`revision + 1`、`updated_at` 更新。
- `emotional_weight` 取较大值。
- 既有 Content、Embedding、Model 和 HappenedAt 不被静默替换。
- 返回 `REINFORCED` 与原 Item ID。

同一 Scope 下的同一 `request_id` 重试必须返回原结果，不能重复 Embedding 或再次强化。事务失败必须完整回滚条目和 Mutation Ledger。

### 4.4 查看与删除

- 查看只返回当前 Scope 的 ID、类型、正文、强化次数、情绪权重和时间；不返回 Embedding、Hash、Scope Binding 或内部模型配置。
- 查看按 `updated_at DESC, id ASC` 排序，最多返回 100 条，不接受客户端覆盖排序或上限。
- 删除必须同时匹配当前 Scope 与 Item ID。
- Forget 采用物理删除，确保正文和 Embedding 不再存在；Mutation Ledger 只保留不含正文的操作结果。
- 同一删除 Request ID 重试返回原结果；不存在或不属于当前 Scope 的 ID 对外统一返回 `NOT_FOUND`。
- R4.2 不实现批量删除、修改正文、合并或跨 Scope 移动。

## 5. Memory HTTP API

R4.2 增加：

```text
PUT    /api/v1/sessions/{sessionId}/memories
GET    /api/v1/sessions/{sessionId}/memories
DELETE /api/v1/sessions/{sessionId}/memories/{memoryId}
```

写入请求：

```json
{
  "requestId": "client-generated-id",
  "type": "PREFERENCE",
  "content": "回答时先给结论",
  "emotionalWeight": 0,
  "happenedAt": null
}
```

规则：

- Session ID 沿用 Chat API 的字符集和 128 字符上限。
- `requestId` 必填、最大 128 字符，只允许 `[A-Za-z0-9_-]+`。
- DELETE 的 Request ID 通过必填 `Idempotency-Key` Header 提供，使用相同格式和上限。
- 写入返回 `CREATED` 或 `REINFORCED`；删除返回 `DELETED` 或 `NOT_FOUND`。
- API 不允许直接提交 Embedding、Hash、Scope、Reinforcement、CreatedAt 或 UpdatedAt。
- Memory API 只在 `JAVA_NATIVE` 和 Loopback 监听下可用；其他模式返回稳定不可用结果。
- 该 API 是显式管理入口，不从普通聊天文本中自动推断“请记住”。

成功响应固定为：

```json
{
  "status": "CREATED",
  "memory": {
    "id": "memory-0001",
    "type": "PREFERENCE",
    "content": "回答时 先给结论",
    "reinforcement": 1,
    "emotionalWeight": 2,
    "happenedAt": "2026-07-15T04:00:00Z",
    "createdAt": "2026-07-15T05:00:00Z",
    "updatedAt": "2026-07-15T05:00:00Z"
  }
}
```

- `CREATED` 使用 HTTP 201；`REINFORCED` 使用 HTTP 200，二者返回相同公开 Memory Shape。
- GET 使用 HTTP 200 和 `{"memories":[...]}`；数组元素使用相同公开 Memory Shape。
- `DELETED` 使用 HTTP 200 和 `{"status":"DELETED","id":"..."}`。
- `NOT_FOUND` 使用 HTTP 404 Problem Detail；不存在与跨 Scope 使用相同公开标题和详情。
- Response 不返回 `requestId`、Embedding、Hash、Scope Binding、内部模型配置或 Mutation Ledger 字段。

## 6. Embedding 契约

Kernel 定义项目自有 `EmbeddingPort`，不得暴露 Spring AI 或 Provider SDK 类型。

- 空批次零调用；单批最多 10 条。
- 每条输入 Strip 后不能为空，按 Unicode Code Point 截到 2000，不拆 surrogate pair。
- 配置维度范围 `1..4096`。
- 输出数量与输入顺序必须一致；每个向量维度一致、全部有限且范数大于零。
- Adapter 使用 Spring AI `EmbeddingModel` 的实际 Provider Options，不读取或记录 API Key。
- 写入 Embedding 失败：Memory API 稳定失败且零数据变化。
- 查询 Embedding 失败：返回无正文 `DEGRADED`，聊天继续。
- 测试只用 Fake Model；真实调用、费用和密钥另行批准。

## 7. 语义检索契约

### 7.1 Query 与 Scope

- Query 只使用当前真实 User 消息；历史保留在 Request，但本阶段不拼入 Embedding。
- 检索只读取与当前 Session Binding 精确匹配的条目。
- 不存在全局、Legacy、Channel Fallback 或跨 Session 召回。
- Session ID 不相同即视为不同 Scope；R6 引入稳定用户身份后再扩展。

### 7.2 候选与评分

顺序固定：

1. 当前 Scope 没有条目时返回 `EMPTY`，零 Embedding 调用。
2. 候选超过 `max-candidates=10000` 时稳定失败，不静默截断。
3. 对当前消息调用一次 Embedding。
4. 只保留 Embedding Model 与维度匹配、BLOB 长度正确、数值有限且非零范数的条目。
5. 计算 cosine，先应用基础 `score-threshold=0.45`。
6. 计算 Hotness：

```text
effective_half_life = max(half_life * (1 + 0.5 * emotional_weight / 10), 0.1)
frequency = 1 / (1 + exp(-log1p(reinforcement)))
recency = exp(-ln(2) / effective_half_life * max(age_days, 0))
hotness = frequency * recency
final = (1 - alpha) * semantic + alpha * hotness
```

默认 `alpha=0.20`、`half-life=14d`。排序固定为 `final DESC, semantic DESC, updated_at DESC, id ASC`，再取 `top-k=8`。

检索严格只读，不强化、不更新时间、不写 Mutation Ledger。

### 7.3 Context Injection

```text
## 【偏好与规则】候选记忆
- [id] content

## 【相关信息】候选记忆
- [id] [happened_at] content
```

- `PREFERENCE`、`PROCEDURE` 合计最多 4 条；其余类型合计最多 4 条。
- Injection 默认最多 6000 Java 字符，并受 R4.1 外层上限约束。
- 按完整行添加，不截断 ID、Unicode 字符或半条记忆。
- 不注入 Embedding、分数、Scope、Hash、内部模型名或 Mutation 信息。
- `PROCEDURE` 也只是候选上下文，不能强制 Tool Call、改变风险或绕过 Approval。
- Frame 继续位于历史之后、当前 User 之前，不进入 SQLite Conversation。

## 8. 失败与隐私

| 故障 | 行为 |
| --- | --- |
| Memory 模式未启用 | Memory API 不可用；Retrieval NoOp |
| Scope 为空 | `EMPTY`；零 Embedding |
| Query Embedding 不可用 | `DEGRADED`；聊天继续 |
| Write Embedding 不可用 | 写入失败；零数据库变化 |
| Schema/DB 损坏、候选超限 | `MEMORY_CONTEXT_UNAVAILABLE`；模型/Tool/提交均为零 |
| Mutation 事务失败 | API 失败；条目与 Ledger 完整回滚 |

日志和 Trace 只能包含稳定状态、候选/命中/注入数量、耗时 Bucket 和逻辑模型名。禁止记录正文、原始 Session ID、Item ID、向量、数据库路径、API Key 或 Provider 错误正文。

## 9. Optimizer Contract（仅冻结）

未来 Java Optimizer 直接操作 `agent-memory.db`，不使用 Python `PENDING.md`、`MEMORY.md` 或 `memory2.db`：

1. 默认关闭且不注册 Scheduler；手动和定时入口共用非等待独占锁，重复运行返回 `BUSY`。
2. 在固定 Scope 下读取条目与 Revision Snapshot；模型只产生候选 Mutation Plan，不能直接写数据库。
3. Plan 只允许合并重复内容、修正分类、降低冗余或标记过期；不能创建 Tool 权限、Secret、可执行代码或跨 Scope 内容。
4. Plan 必须通过 Schema、数量、字符、Scope、引用 ID、Embedding 和内容安全校验。
5. 单个显式事务检查原 Revision，写入新条目/删除旧条目和 Optimization Audit；Revision 冲突则零写入重试或失败。
6. 每次成功优化必须保留可撤销的条目版本；Audit 不记录原始 Provider 响应。
7. 模型、验证、Embedding、备份或事务失败均不得产生部分优化。
8. Optimizer 不修改 Markdown Profile，也不与显式 Memory API 并发覆盖同一 Revision。
9. 真实模型、Scheduler、自动运行、Undo API 和生产恢复演练必须重新批准。

R4.2 不实现上述 Optimizer 类、表、Bean、API 或后台任务。

## 10. 完成门禁

- Contract、ADR、Spec、HTTP API 与实施计划明确获批。
- Java Contract Test 固定 Schema、Vector 编码、Hash、幂等、删除、cosine、Hotness、Scope 和 Injection；不调用 Python。
- Kernel 只依赖 JDK；Spring AI 和 JDBC 类型不越界。
- 新库、迁移备份、事务回滚、Mutation 幂等和物理删除有测试。
- Embedding Adapter 的批次、Code Point、顺序、维度与非法值有 Fake 测试。
- Memory API、语义检索、Context Frame、Tool Loop 保留和 Conversation 提交隔离端到端通过。
- 默认、`failure`、`compat`、依赖、Secret、Workspace、生产 Bean 和网络监听审计通过。
- 模板继续 `DISABLED`；不访问或删除 Python Workspace，不运行真实 Embedding。

## 11. 已批准决定

1. 接受 `JAVA_NATIVE` 同时启用 Java 语义库、显式 Memory API 和 Retrieval；默认保持 `DISABLED`。
2. 接受 V1 使用 Float32 BLOB、有界全表 cosine 和 Session 级 Scope，不实现跨 Session 全局记忆。
3. 接受增加三个显式 Memory HTTP Endpoint，先解决空库写入与删除，不从聊天自动提取。
4. 接受写入 Embedding 失败零数据变化、查询 Embedding 失败降级聊天的差异。
5. 接受 Java 原生 Optimizer 本阶段只冻结 Contract，不实现、不装配、不调度。
