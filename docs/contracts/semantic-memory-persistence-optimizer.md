# 语义记忆、持久化与优化器契约

- 状态：待批准
- 契约版本：1
- 日期：2026-07-15
- 适用阶段：R4.2 `memory2` 持久化基础与只读语义检索
- Python 参考 Commit：`b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Python 证据：`memory2/store.py`、`memory2/embedder.py`、`memory2/retriever.py`、`plugins/default_memory/engine.py`、`proactive_v2/memory_optimizer.py`

> 本契约只授权在临时测试 Workspace 中实现和验证 `memory2` SQLite 持久化原语，并在生产装配中增加默认关闭的只读语义检索。它不授权访问真实用户 Workspace、调用真实 Embedding 服务、把 Memory Writer 接入聊天主链、运行 Optimizer、开放记忆 Tool 或让 Python 与 Java 同时写入同一数据库。

## 1. 目的

R4.2 把 R4.1 的 `MemoryRetrievalPort` 从生产 NoOp 推进为可选的真实只读检索链路：当前用户消息经过项目自有 Embedding Port，查询 Python 兼容的 `memory2.db`，按确定性的 cosine、Hotness、类型、Scope 和预算规则生成候选记忆块，再由既有 Context Frame 注入模型。

同一契约同时冻结未来持久化与 Optimizer 的数据、副作用和恢复边界，避免先写数据库或 Markdown，再补幂等、备份和崩溃语义。

## 2. Python 基准与 Java 迁移决定

2026-07-15 对 Python 参考 Commit 执行以下基线：

```bash
.venv/bin/python -m pytest \
  tests/test_memory2_retrieval_baseline.py \
  tests/test_memory2_dedup_baseline.py \
  tests/test_memory2_consolidation_idempotency.py \
  tests/test_memory_optimizer.py \
  tests/test_recall_memory_tool.py -q
```

结果：45 个测试全部通过。

| 能力 | Python 当前行为 | R4.2 决定 |
| --- | --- | --- |
| `memory2.db` | `memory_items`、`consolidation_events`、`memory_replacements` | 兼容核心表、列、索引与 JSON Embedding；不要求 `sqlite-vec` |
| 精确去重 | 规范化正文与类型生成 16 位 SHA-256 前缀；重复写强化原条目 | 迁移为内部 Writer 原语，但不做生产 Bean 或聊天写回 |
| Embedding | OpenAI-compatible `/embeddings`，单批不超过 10，文本截到 2000 字符 | 通过 Kernel Port 和 Spring AI Adapter 迁移；测试只用 Fake Model |
| 向量检索 | cosine 阈值、类型、状态、Scope、Top-K | 迁移共同子集，并增加确定性同分排序和候选上限 |
| Hotness | frequency × half-life decay；情绪权重延长半衰期 | 迁移公式，默认 `alpha=0.20`、`half-life=14d` |
| 注入 | 按类型阈值、段落数量和字符预算生成文本块 | 迁移普通规则/偏好/事件/Profile；不迁移强制 Tool 指令 |
| Keyword/RRF | Keyword Lane 与 Vector Lane 进行 RRF | 本阶段延后 |
| Query Rewrite/HyDE | 可生成辅助 Query 或假设 | 本阶段延后；只嵌入当前真实 User 消息 |
| 检索后强化 | 部分路径更新 reinforcement | 只读主链禁止写入，本阶段不强化 |
| Optimizer | PENDING 快照、LLM 合并、MEMORY 备份、SELF 更新 | 只冻结 Contract；不实现、不装配、不调度 |

## 3. `memory2.db` 数据契约

### 3.1 固定位置与所有权

- 默认相对路径为 `memory/memory2.db`，调用方不得从 HTTP 请求提供任意路径。
- 生产 `SEMANTIC_READ_ONLY` 只允许以 SQLite Read-Only 模式打开已存在数据库，不创建目录、数据库、表、索引、WAL 或 SHM。
- 缺失数据库等价于空记忆，不调用 Embedding；同名但不兼容的 Schema、损坏数据库或读取失败稳定失败。
- Java Writer 只在测试和未来独立获批的 Maintenance 路径中可构造；R4.2 Bootstrap 不暴露 Writer Bean。
- Python 和 Java 不得同时写入同一 Workspace。真实 Workspace 与真实数据库在本阶段仍禁止访问。

### 3.2 Python 兼容 Schema

必须识别以下核心结构，未知表、未知列和未知 JSON 字段保持不变：

```sql
CREATE TABLE memory_items (
  id TEXT PRIMARY KEY,
  memory_type TEXT NOT NULL,
  summary TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  embedding TEXT,
  reinforcement INTEGER NOT NULL DEFAULT 1,
  emotional_weight INTEGER NOT NULL DEFAULT 0,
  extra_json TEXT,
  source_ref TEXT,
  happened_at TEXT,
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_items_hash
  ON memory_items(content_hash, memory_type);
```

`consolidation_events` 与 `memory_replacements` 按 Python 当前列和索引建立兼容结构，但 R4.2 不把 Consolidation、Replacement、Undo 或 Dashboard 管理行为接入生产。

Embedding 继续存为 JSON Number Array，避免 Java 写入 Python 无法读取的私有二进制格式。`vec_items` 如果存在必须忽略且不得修改；未来 Python 重新独占数据库后，由 Python 自己的启动同步逻辑重建向量索引。

### 3.3 条目和值约束

- `memory_type` 只接受 `procedure`、`preference`、`event`、`profile`。
- `status` 只接受 `active`、`superseded`；生产查询默认只读 `active`。
- `summary` Strip 后不能为空；单条上限为 20,000 Java 字符。
- `reinforcement >= 1`；`emotional_weight` 归一化到 `0..10`。
- `extra_json` 必须为 JSON Object；未知字段原样保留。
- 时间使用 ISO-8601；无 Offset 的 Python 时间按原文本保留，参与 Hotness 的 `updated_at` 无 Offset 时按 UTC 解释，避免依赖宿主默认时区。
- Embedding 维度必须等于配置维度，每个数必须有限；零范数条目不参与检索。

### 3.4 写入、去重与备份

内部 Writer 的 `upsert` 行为冻结如下：

1. `content_hash = SHA-256(normalize(summary) + memory_type)` 的前 16 个小写十六进制字符；`normalize` 为 Strip、转小写并把连续空白压成单个空格。
2. 新内容创建不透明 ID，写入 Embedding、Extra、Source、HappenedAt 与时间戳。
3. 同 `(content_hash, memory_type)` 重复写入时不创建第二条；`reinforcement + 1`、`updated_at` 更新、`emotional_weight` 取较大值，`superseded` 恢复为 `active`。
4. 重复 Event 只在原 `happened_at` 为空时补入新值；不静默替换已有时间。
5. 每个 Upsert 使用单个显式事务；失败全部回滚。

打开已存在数据库进行第一次 Java 可写操作前，必须先用 SQLite Backup API 生成一致性备份。备份失败时不得执行 DDL 或 DML。新建的空测试数据库无需备份。R4.2 不提供删除、Forget、Replacement、批量强化或自动迁移真实数据库的运行时入口。

## 4. Embedding Contract

Kernel 定义项目自有 `EmbeddingPort`，不得暴露 Spring AI、OpenAI 或供应商 SDK 类型。

- 输入顺序必须保留，输出数量必须与输入数量一致。
- 空批次直接返回空，不调用模型；单批最多 10 条。
- 每条输入 Strip 后不能为空，并按 Unicode Code Point 截到 2000，不能截断 surrogate pair。
- 配置维度范围为 `1..4096`；每个返回向量必须维度一致、全部有限且范数大于零。
- Adapter 使用已配置的 Spring AI `EmbeddingModel`，不得接管 Agent Loop、重试、Context 或会话提交。
- R4.2 测试只使用 Fake `EmbeddingModel`；真实 Provider 调用、费用和密钥必须另行批准。

## 5. 只读语义检索 Contract

### 5.1 Query 与 Scope

- Query 只使用当前真实 User 消息的 Strip 文本；完整历史仍保留在 Request 中，但本阶段不拼接进 Embedding。
- Java HTTP 当前没有 Python Channel/Chat 身份，因此不能猜测或降级匹配 Python 的 `scope_channel`、`scope_chat_id`。
- `extra_json.scope_session_binding` 非空的 Java 条目只允许与当前不可逆 Session Binding 精确匹配。
- 带 Python `scope_channel` 或 `scope_chat_id` 的条目在 R4.2 HTTP 主链中一律排除。
- 三个 Scope 字段都缺失的 Legacy 条目视为单用户全局记忆，只在显式 `allow-global=true` 时参与检索；该开关只在 `SEMANTIC_READ_ONLY` 下生效。
- 只有单个字段存在、JSON 类型错误或 Scope 形状不完整的条目一律排除。

### 5.2 候选、评分与排序

查询顺序固定为：

1. 数据库缺失或无 Active Embedding 时返回空，不调用 Embedding。
2. SQL 预过滤 `active`、合法类型与 Scope；候选超过 `max-candidates` 时稳定失败，不静默截断。
3. 对当前消息调用一次 Embedding。
4. 对维度相同且非零范数的候选计算 cosine。
5. 先应用基础 `score-threshold=0.45`。
6. 计算 Hotness：

```text
effective_half_life = max(half_life * (1 + 0.5 * emotional_weight / 10), 0.1)
frequency = 1 / (1 + exp(-log1p(max(0, reinforcement))))
recency = exp(-ln(2) / effective_half_life * max(age_days, 0))
hotness = frequency * recency
final = (1 - alpha) * semantic + alpha * hotness
```

7. 排序固定为 `final DESC, semantic DESC, updated_at DESC, id ASC`，再截取 `top-k=8`。
8. 对 Injection 再应用类型阈值：`procedure=0.66`，其余三类为 `0.50`。

检索是严格只读的：不更新 reinforcement、updated_at、数据库、Markdown 或 Conversation。

### 5.3 Injection 与预算

Injection 只允许以下两个候选上下文 Section：

```text
## 【流程规范】用户偏好与规则
- [id] summary

## 【相关历史】记忆检索结果
- [id] [happened_at] summary
```

- `procedure` 与 `preference` 合计最多 4 条；`event` 与 `profile` 合计最多 4 条。
- 整块默认最多 6000 Java 字符，且不得超过 R4.1 外层 `maxRetrievedCharacters`。
- 预算按完整行添加；不截断 ID、Unicode 字符或半条记忆。放不下的后续条目省略。
- 顺序沿用检索排序；空 Section 不渲染。
- `source_ref`、`extra_json`、内部分数与 Scope 不进入模型文本。
- Python 的“强制约束”“必须调用工具”投影明确不迁移。记忆不能授予 Tool 权限、降低风险、伪造审批或覆盖 System Policy。

## 6. 失败与降级

- 模式 `DISABLED` 或 `READ_ONLY`：Retrieval 保持 NoOp，零数据库和零 Embedding 调用。
- `SEMANTIC_READ_ONLY` 且数据库缺失/没有候选：返回 `EMPTY`，聊天继续。
- Embedding 超时、供应商不可用或返回非法向量：返回无正文的 `DEGRADED` Trace，聊天继续，不泄露供应商正文。
- Schema 不兼容、数据库损坏、候选超限、非法持久化数据导致无法安全判断边界：抛出稳定 Memory Context 失败，模型、Tool 和 Conversation 提交均为零。
- Trace 只包含稳定状态和计数，不包含 Query、Summary、ID、Scope、Embedding、路径或供应商错误。

## 7. Optimizer Contract（仅冻结，不实施）

未来 Optimizer 必须满足：

1. 默认关闭，不注册 Scheduler；手动或定时入口共用非等待的独占锁，重复运行返回 `BUSY`。
2. `PENDING.md` 先通过同目录原子 Rename 形成 Snapshot；运行期间新增 Pending 写入新的 `PENDING.md`。
3. LLM 合并只接收 MEMORY 与 Snapshot，不使用 HISTORY；Tools 必须为空。
4. 合并结果必须通过标题、允许 Section、字符上限和严格 UTF-8 校验。
5. 写 MEMORY 前先生成一致性备份，再使用临时文件、fsync 和原子替换；不允许原地覆盖。
6. MEMORY 与归档 HISTORY 成功后才能删除 Snapshot；任何合并、验证、备份或写入失败都按“旧 Snapshot 在前、新 Pending 在后”回滚。
7. SELF 更新是 MEMORY 提交后的独立步骤；SELF 失败不回滚已成功的 MEMORY，但必须保留可重试状态。
8. 启动时发现遗留 Snapshot 必须先恢复；不得静默丢弃。
9. Optimizer 不写 `memory2.db`；Markdown 优化与向量摄入必须保持两个独立事务和幂等键。
10. 真实 Workspace 首次写入、模型费用、Scheduler 与运维恢复演练必须重新批准。

## 8. 明确非目标

- 不实现 Keyword/RRF、Query Rewriter、HyDE、Sufficiency Checker 或时间线查询。
- 不引入 `sqlite-vec`、本地向量数据库、JPA、R2DBC 或新服务。
- 不实现对话后自动摄入、Consolidation、Post Response Worker、Optimizer 或 Scheduler。
- 不开放 recall/memorize/forget/reinforce Tool、Dashboard 管理 API 或批量删除。
- 不读取真实 Workspace，不调用真实 Embedding，不运行真实模型 Smoke。
- 不把 Memory 命中当成事实、权限、用户原文、Tool Result 或审批决定。

## 9. 完成门禁

- Contract、ADR、Spec 和实施计划明确获批。
- Python 生产 helper 生成的 Schema、Hash、排序、Scope 和 Injection Golden 可重复。
- Kernel 端口不依赖 Spring、JDBC、Reactor、Spring AI 或 Provider SDK。
- Read-Only Adapter 对缺失、兼容 Schema、损坏、候选上限、非法 JSON/Embedding、Scope 和零写入有测试。
- Writer 原语对精确去重、强化、回滚、备份先行和备份失败零写入有测试，但生产无 Writer Bean。
- Spring AI Embedding Adapter 对批次、Code Point 截断、顺序、维度和非法值有 Fake 测试。
- 真实检索端到端证明 Query、Embedding、排序、预算、Context Frame、Tool Loop 保留和 Conversation 提交隔离。
- 默认、`failure`、`compat`、依赖、Secret、Workspace、生产 Bean 与 SQLite 写入面审计通过。
- 模板和生产默认继续 `AGENT_MEMORY_MODE=DISABLED`。

## 10. 待批准决定

1. 接受 R4.2 生产只装配 `SEMANTIC_READ_ONLY`，Writer 仅作为未接线的持久化原语，不做聊天后写回。
2. 接受使用 Python 兼容 JSON Embedding 加纯 Java 全表 cosine；本阶段不引入 `sqlite-vec`，候选超限失败。
3. 接受 Scope 的安全差异：不匹配 Python Channel/Chat 记忆；Legacy 全局记忆只由显式开关启用。
4. 接受 Embedding 故障降级为空、数据边界故障稳定失败的分类。
5. 接受删除 Python 的强制 Tool 记忆语义，Memory 永远不能绕过 Tool Policy 和 Approval。
6. 接受 Optimizer 本阶段只冻结 Contract，不实现、不装配、不调度。
