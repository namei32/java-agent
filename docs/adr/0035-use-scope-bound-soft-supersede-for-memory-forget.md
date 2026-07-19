# ADR-0035：以当前 Scope 的软失效实现获批的 Memory Forget

- 状态：已接受
- 日期：2026-07-19
- 决策人：用户，选择 R11-B2c 路径 A
- 关联：[获批的 Scope 受限 Memory Forget 契约](../contracts/approved-scope-bound-memory-forget.md)

## 背景

Python `ForgetMemoryTool` 接受批量 `ids`，按顺序去空白、去重，并把命中的 `memory2` 条目写为
`superseded`。Java R4.2 的显式 HTTP 删除则是当前 Session Scope 内的单 ID 物理删除。旧 Python
`memory2.db` 已获准丢弃，但这不自动决定新的运行时 Tool 语义。

用户已明确选择路径 A，同时确认两项有意的 Java 安全差异：仅操作当前 Session Scope，且 Tool
结果不返回记忆正文或其他内容字段。

## 决策

1. 新增静态、版本固定的 `forget_memory` WRITE Capability；它仅在显式本地模式中可见，并始终通过
   Pending Operation、人工审批和 Loopback Resume 边界执行。
2. Java Memory Schema V2 为记忆增加 `ACTIVE` / `SUPERSEDED` 状态，并为批量 Forget 的可重放结果
   增加无正文的 Mutation 明细。V1 到 V2 先备份再迁移，任何未知 Schema 均 Fail Closed。
3. Forget 仅在当前 Session Scope 内匹配 ID。其他 Scope 与不存在的 ID 对外同为 `missing_ids`；Tool、
   Capsule、日志和安全结果不回显正文、Embedding、Hash、Scope、Session 或数据库路径。
4. `SUPERSEDED` 条目不参与常规查看、召回、Context 注入或候选计数；同 Scope、同内容的后续 UPSERT
   重新激活该条目，并保持唯一内容键。
5. 既有显式 HTTP `DELETE` 保留物理删除语义，不能被重新命名为 `forget_memory`。本 ADR 不授权
   `memorize`、文件、Shell、网络、消息发送、真实 Telegram、前端或旧 Python 数据访问。

## 后果

这实现 Python 的批量、稳定顺序和软失效核心语义，但不是逐字节 Python 兼容：Python Tool 不加
Session Scope 且会回传命中项内容，Java 刻意不做这两点。它需要 Kernel/SQLite/Capability 的版本化
迁移与新的 Fixture；默认部署仍完全关闭。
