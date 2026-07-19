# R11-B2c `forget_memory` Tool/Chat Pending 生产器实施提案

- 状态：已完成；P0–P6 已按连续 TDD 实现，默认、`failure`、`compat` 三套阶段门禁均已通过
- 日期：2026-07-19
- 前置实现：[Scope 受限 Memory Forget Capability 实施计划](2026-07-19-r11-memory-forget-capability-implementation.md)
- 前置 Contract：[获批的 Scope 受限 Memory Forget Capability](../contracts/approved-scope-bound-memory-forget.md)

## 1. 为什么这是独立阶段

F1–F6 已完成 Memory Schema V2、加密 Capsule、Approval、Pending Operation、Session Anchor、显式本机
Resume/Cancel 与受限 Capability。它们组成的是**恢复与执行后半段**；本计划补齐受控的 Tool Catalog 条目与 Chat
Pending 生产器，因此模型只能在独立、默认关闭的条件下创建 `forget_memory` 请求。

把静态 `forget_memory` Schema 放进 Catalog，并让模型 Tool Call 创建持久化 Pending/Approval/Anchor，虽仍默认
关闭、仍不立即失效任何记忆，却会引入模型可触发的耐久写入。因此它不能由 F1–F6 的恢复授权自动推定，必须先
冻结独立 Contract 并获得明确批准。

## 2. 建议的目标边界

本阶段只实现“创建待审批操作”，不实现或扩大任何恢复执行面：

- `forget_memory` 仍是固定 `WRITE` Capability，版本固定为 `java-memory-forget-v1`；不接受动态 Tool、Plugin、
  MCP 或调用者提供的版本、Scope、幂等键或执行边界。
- 建议作为当前 Turn `tool_search` 后下一次模型请求才可见的 deferred Catalog 项；默认、模式不完整或非 Servlet
  Runtime 时仍为零 Catalog、零 SQLite、零 ID 生成。
- 非空且合法的模型 Tool Call 只能创建一个 Approval、一个 Pending Operation、一个加密 Capsule 和一个 Pending
  Session Anchor；它不调用 Invoker、不软失效 Memory、不自动 Resume，也不轮询审批。
- Chat 对外只提交固定、无正文的“等待本机审批”Assistant 投影，并终止该 Turn；不把一个伪造的成功 Tool Result
  回送模型以诱导它宣称操作已经完成。
- 空规范化 `ids` 保持已有安全成功语义，不创建任何 Pending/Approval/Anchor；Schema/模式/绑定/写入失败均
  Fail Closed，且不得留下可恢复的执行权。
- Pending 创建只复用现有本机 Loopback Resume/Cancel/Status；不增加 HTTP 创建 API、Dashboard、CLI、Telegram、
  Worker、SSE、远程监听、真实数据或网络 Smoke。

## 3. 先冻结的 Contract 决策

在写生产代码前，Contract 必须明确下列语义；建议以本节的推荐值作为 RED Fixture 的初始值：

| 决策 | 推荐冻结值 | 原因 |
| --- | --- | --- |
| Catalog 可见性 | `tool_search` 后 deferred，下一模型请求生效 | 不将写入 Schema 常驻暴露给模型 |
| Tool Call 终止形态 | 创建 Pending 后以固定 Assistant Pending 投影终止当前 Turn | 避免 Tool Loop 把“已创建”误读为“已执行” |
| Pending 摘要 | 固定安全摘要，不含 ID、正文、Scope、Session 或参数 JSON | 让 Approval/日志/UI 都不泄漏记忆内容 |
| 创建顺序 | 先同库 Approval/Operation/Capsule，后 Session Anchor CAS；失败固化 `STALE_SESSION` | 保持 F3 的零悬挂执行权规则 |
| 可取消性 | Pending/Approved 未 Reservation 前沿用现有 Cancel；`CONSUMING` 不可 kill | 不把控制面变为强制终止或重试接口 |
| 模式 | 仅已有 `LOOPBACK_APPROVAL` 全部前置成立 | 不新增宽松开关、Token 或远程身份 |

任何会改变 Tool Result 是否回送模型、是否允许同一 Turn 继续推理、是否可常驻可见，或是否创建新的管理 API 的选择，
都必须作为新的 Contract 变更，而非实现细节。

## 4. 连续 TDD 顺序

1. **P0 Contract 与授权检查点（完成）。** 已确认上表决策和本阶段授权，创建 Java-owned
   `memory-forget-pending-producer-v1` Fixture，并冻结[Producer 设计](../specs/2026-07-19-r11-memory-forget-pending-producer-design.md)。
2. **P1 Fixture/Kernel（RED→GREEN，完成）。** 固定 Catalog 可见性、严格 Schema、空列表短路、固定 Pending 投影、
   不透明引用与安全摘要；每一个 Case 都必须明确生产边界或聚焦测试所有者。
3. **P2 Application Producer（RED→GREEN，完成）。** 在现有 `MemoryForgetPendingService` 上建立受限 Chat Tool Call
   适配，验证创建顺序、Anchor CAS、`STALE_SESSION`、幂等和零 Invoker；不得调用泛化 Side Effect Coordinator。
4. **P3 Catalog/ToolLoop/Session（RED→GREEN，完成）。** 仅在所有严格前置满足时投放 deferred Schema；模型 Call 创建
   Pending 后必须终止当前 Turn 并提交固定投影。默认、搜索前、模式失败、未知字段及模型继续执行路径均必须零写入。
5. **P4 Bootstrap 与控制面连线（RED→GREEN，完成）。** 验证 Servlet-only、显式配置、无额外路由/Worker/Token，且
   `ObservedSessionRepository` 不会丢失 Anchor 取消；只复用既有 Loopback Resume/Cancel/Status。
6. **P5 故障与竞争（RED→GREEN，完成）。** 复用临时 SQLite 的既有 Capability/Recovery 矩阵，并新增 Producer 的
   空输入、同批次拒绝、零泛化审批和零 Invoker 断言，覆盖重复 Tool Call、并发创建、审批前取消、过期、新 Turn、
   绑定不符、Session CAS/提交失败、关闭/中断和 `UNKNOWN`；每个分支均断言 Invoker 为零，除非通过既有显式 Resume。
7. **P6 阶段审计（完成）。** 已更新 Fixture manifest/SHA、Contract、Capability 矩阵、README、Runbook 和 Roadmap；
   `clean verify`、`-Pfailure verify`、`-Pcompat verify` 均已通过。未推送、未建 PR、未合并。

## 5. 完成定义与明确非目标

完成表示：在默认关闭的本地测试环境中，模型 Tool Call 可以安全地**创建**一个待本机审批的 `forget_memory`
操作，且审批前/失败时零 Memory Mutation。完成不表示真实 Telegram、网络、生产数据、自动审批、后台恢复、
`memorize`、文件写入、Shell、Web 写入、消息推送或 Python `memory2.db` 获得授权。

真实 Provider Tool Smoke 仍需单独的网络、费用和数据授权；即使 P6 的三套离线门禁均通过，也不得据此开启部署。
