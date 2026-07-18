# R11-B4 当前会话只读证据 Tool 契约

- 阶段：R11-B4
- 状态：已完成并验证。默认 `DISABLED`，尚未开启真实用户数据或远程访问
- 契约版本：1
- 日期：2026-07-19
- 关联 ADR：[ADR-0025：以 Turn 上下文受限方式暴露当前会话证据 Tool](../adr/0025-bind-conversation-evidence-tools-to-current-turn-context.md)

## 1. 目标和 Python 差异

本切片迁移 Python `fetch_messages` 与 `search_messages` 的核心用途：模型先定位当前会话中的历史文本，再按引用读取
有限上下文。它只读取 Java `sessions.db` 中当前 Chat Session 的 `USER` / `ASSISTANT` 消息，不写 Session、Memory、
Approval、Ledger 或外部系统。

Python Tool 可以接收原始 `session_key`、`telegram:<chat>:<seq>` 形式的 ID、`source_ref`/evidence JSON，并可跨
Session 搜索。Java 不复制这些表面：它们会把 Route/会话标识暴露给模型，且可扩大历史读取范围。Java 使用仅在当前
Session 内有效的 `msg-v1:<seq>` 引用；未知、跨 Session 或格式错误的引用统一不可用，不能区分原因。这个 Scope/ID
差异是受控安全替代，不应声称字节级 Python 兼容。

## 2. 默认关闭和注册

`agent.conversation-evidence.mode` 只能取严格大写值：

| 值 | 行为 |
| --- | --- |
| `DISABLED` | 默认；不创建活跃查询 Adapter 或 Tool，不读取 Session 历史。内部只保留不执行 I/O 的 disabled sentinel Port。 |
| `CURRENT_SESSION_READ_ONLY` | 仅当全局 `agent.tools.mode=READ_ONLY` 时注册两个 deferred Tool；每个 Turn 仍须先调用 `tool_search`。 |

全局 Tool Runtime 为 `DISABLED` 时 Evidence Mode 必须等价于 `DISABLED`。非 `READ_ONLY` 的组合、未知/小写 Mode、或
Evidence 单项预算超过既有 `agent.tools.max-result-characters` 均在启动时 Fail Closed。它不接收模型或 HTTP 传入的
Session ID、数据库路径、Route、Channel、Sender、Cursor 或原始 Message ID。

## 3. 最小 Schema 和当前 Turn 绑定

`fetch_messages` 固定为 `READ_ONLY`、版本 `conversation-evidence-v1`：

```json
{"ids":["msg-v1:12"],"context":0}
```

- `ids` 是 1–16 个不重复 `msg-v1:<非负十进制序号>`；请求顺序保留。
- `context` 可选，整数 `0..10`，默认 `0`。它只能在当前 Session 内向前/后扩展；同一消息只出现一次。
- 不接受 `source_ref`、`source_refs`、`evidence`、`session_key`、未知字段、浮点/字符串数值或空数组。

`search_messages` 固定为 `READ_ONLY`、版本相同：

```json
{"query":"缓存 命中","role":"user","limit":10,"offset":0}
```

- `query` Strip 后为 1–256 Unicode code points；按 Unicode 空白分词，任一个词按 `Locale.ROOT` 小写后的字面包含即命中，
  与 Python 多词搜索的 OR 行为一致。
- `role` 可省略或为严格小写 `user` / `assistant`；`limit` 为 `1..50`，默认 `10`；`offset` 为 `0..1000`，默认 `0`。
- 结果按 `seq DESC`，再按稳定 ID 排序；分页字段 `count`、`matched_count`、`limit`、`offset`、`has_more`、`next_offset`
  语义固定。

Tool Registry 为每次模型调用创建不公开的 `ToolInvocationContext`。只有受信 `ContextualTool` 可取到其中的
`ConversationEvidenceScope`；普通 Tool、Plugin、MCP、模型参数和日志均不能得到原始 Session ID。Context 在虚拟线程
任务内显式传递，禁止 `ThreadLocal`、静态可变 Scope 或从模型参数反推 Session。

## 4. 安全结果与预算

公开记录只可含 `id`、`seq`、`role`、`content`、`in_source_ref`（仅 Fetch context）和 Search 的
`source_ref`、`matched_terms`、`preview`、`preview_line_count`、`total_line_count`、`truncated`。`id` 与
`source_ref` 都是同一 `msg-v1:<seq>`；绝不返回 `session_key`、数据库路径、`tool_chain`、`extra`、内部 Anchor、
Approval、Memory、Route、Sender、SQL 或异常正文。

Fetch 最多请求 16 个 ID，且在保留命中顺序/窗口语义的前提下最多投影 16 条、12,000 code points；窗口超过此上限
安全失败，绝不静默截断。Search 最多投影 50 条，每条预览最多 50 行、总计 12,000 code points。任何超限、无效记录、
Store 故障或当前 Turn 取消均返回稳定安全 Tool 结果，不泄漏
哪一条消息、哪一个 Session 或哪一段正文触发失败。最终 Tool Result 仍受全局 `max-result-characters` 二次上限。

每个成功结果携带固定 `citation_required=true`、`citation_format="§cited:[id1,id2,...]§"` 和规则文本；它只是
不可信 Tool Result，不成为 System Prompt 或执行授权。引用行为的 Prompt/最终回答验证属于后续 R10/R12 对齐，不能由
本 Tool 自动伪造引用。

## 5. 存储、取消和失败

SQLite Adapter 只做受限查询：Fetch 以当前 Session Key 和 sequence 列表/窗口查询；Search 同样固定当前 Session，
不创建 FTS 表或写入索引。Adapter 读取的 `SYSTEM`、`TOOL`、空或畸形记录一律不进入结果。

Tool 执行复用既有公平许可、Deadline、虚拟线程、取消和 Result Budget；取消前/中/后均不得遗留 Store Mutation。
请求、结果、日志和 Fixture 不得包含真实用户正文、Session/Route/Sender、Secret 或数据库路径。自动化只创建临时
Java SQLite 和固定匿名内容。

## 6. 验收与非目标

Java-owned `tools/conversation-evidence-v1` Fixture 固定 Disabled 零查询、严格 Mode/Schema、deferred 解锁、当前
Session 隔离、opaque ID、Fetch 顺序/窗口、Search OR/role/分页、预览/预算、取消、Store 故障和零泄漏。Kernel、
Application、SQLite 与 Bootstrap 的生产路径各有聚焦验收；Fixture 本身由 `compat` 消费。`./mvnw clean verify`、
`./mvnw -Pfailure verify`、`./mvnw -Pcompat verify` 与格式检查已通过。

本切片不实现跨 Session/Channel 搜索、原始 Python source_ref/evidence JSON、全文索引、Memory Recall、Citation
强制、Dashboard 历史浏览、删除/编辑消息、Side Effect Resume、远程网络、CLI+Web、前端或真实用户数据 Smoke。
