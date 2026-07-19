# R11-B2c `forget_memory` Tool/Chat Pending 生产器设计

- 状态：已实现并审计；默认、`failure`、`compat` 三套阶段门禁均已通过
- 日期：2026-07-19
- Contract：[获批的 Scope 受限 Memory Forget Capability](../contracts/approved-scope-bound-memory-forget.md)
- 实施计划：[Tool/Chat Pending 生产器实施提案](../plans/2026-07-19-r11-memory-forget-tool-chat-pending-producer-plan.md)

## 1. 目标与边界

本设计只补齐模型到本机审批之间的受限入口。`forget_memory` 是静态、deferred、`WRITE` 的 Builtin Tool；它只能创建
一个待本机审批的 Operation，绝不在 Chat Turn 中调用 `MemorySoftForgetPort`、Recovery、Resume、Worker 或泛化
`SideEffectBatchCoordinator`。

只有 `agent.tools.mode=APPROVAL_REQUIRED`，且既有 `JAVA_NATIVE` Memory、Loopback Approval Inbox、Loopback
Control Plane、Servlet Runtime 和 `agent.capabilities.memory-forget.mode=LOOPBACK_APPROVAL` 全部成立时，Bootstrap
才创建专用 Producer 并将 Schema 作为 deferred Catalog 项投放。任何一个条件不成立时，Producer、Catalog Entry、
SQLite 写入和 ID 生成均为零。

## 2. Turn 内流转

```text
tool_search (当前 Turn) -> 下一模型请求出现 forget_memory Schema
  -> 单个合法非空 Tool Call
  -> 专用 Pending Producer
  -> 同库 Approval + Operation + Capsule
  -> Session Pending Anchor CAS
  -> 固定 Assistant Pending 投影并终止当前 Turn
  -> 本机 Loopback Resume/Cancel/Status（既有 F4 路径）
```

Catalog 解锁仍仅在下一模型请求生效；同一批 `tool_search` 与 `forget_memory` 调用必须拒绝且零写入。包含多个 Tool
Call，或包含 `forget_memory` 与任意其他 Tool 的批次也必须拒绝且零写入：一轮只允许一个明确的 Pending 创建意图。

Producer 先让既有 `ToolRegistry` 做可见性和 Schema 预检，然后只调用 `MemoryForgetPendingService`。它不构造
`ApprovalRequest`、Capsule、Scope、幂等键或执行参数；这些敏感边界继续由该 Service 从已认证的 Chat Context
派生。非空成功只会提交固定文本 `记忆遗忘请求正在等待本机审批。`，不回送 Tool Result 给模型，不发起第二次
模型请求，也不声称 Memory 已被修改。

空规范化 `ids` 使用既有安全成功结果、零 Pending/Approval/Anchor；它可作为普通无副作用 Tool Result 回送给模型。
Schema、模式、可见性、批次形状、写入、Anchor CAS 或取消失败均 Fail Closed。Anchor CAS 未赢时 Operation 必须先被
固化为 `STALE_SESSION`，Chat Turn 以失败结束且不提交 Pending 投影。

## 3. 分层与对象所有权

| 对象 | 职责 | 禁止事项 |
| --- | --- | --- |
| `MemoryForgetPendingToolset` | 提供固定 Schema 占位 Tool 与受限 Producer | 不执行 Memory、不得直接写 Store |
| `MemoryForgetPendingTurnContext` | 从 Chat 已加载 Snapshot、User Turn、Turn ID 派生单次请求 | 不公开 raw Session ID 给一般 Tool |
| `MemoryForgetPendingService` | 原子创建 Approval/Operation/Capsule，再 Session Anchor CAS | 不注册 Catalog、不调用模型 |
| `ToolLoop` | 识别专用创建结果，停止当前模型循环 | 不把 Pending 当 Tool 成功 Result 回送模型 |
| `ChatService` | 当 Producer 返回 Pending 时不再追加普通 Turn | 不重复 Session 写入或自动恢复 |

专用 Context 只在 application package 内流动。Plugin、MCP、普通 `Tool`、Provider Adapter 和 HTTP Controller 都
无法取得它；它也不允许调用者指定 Scope、sequence、idempotency key、Approval 摘要或 Assistant 投影。

## 4. 失败与并发

Session Execution Gate 保证同一 Session 的正常 Chat 不并发。仍须以持久化 Store 和 Session CAS 处理进程内/外竞争：
Operation 写入失败时 Session 零写入；CAS 失败或抛错时仅尝试 `STALE_SESSION`，绝不创建第二个 Operation。所有
Producer 失败路径均为零 Invoker；只有既有、经本机认证的 Resume 可触发一次 Reservation 与 Capability。

Producer 不创建新控制器、路由、Token、SSE、后台扫描、轮询或自动恢复。已有 Loopback Resume/Cancel/Status 的
认证、Host/Origin、审计与响应形状不变。

## 5. 验收归属

`tools/memory-forget-pending-producer-v1.json` 固定激活、Catalog deferred 时机、严格 Schema、空输入、单 Call
批次形状、Pending 投影、创建顺序、CAS/写入失败、零 Invoker 和无自动恢复。Kernel/Application/Bootstrap 各自只
消费所属 Case，避免以跨层重复场景堆叠测试数。阶段结束时已统一执行默认、`failure`、`compat` 三套完整门禁并通过。
