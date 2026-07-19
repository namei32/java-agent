# R13-C3-M0 Memory Forget 管理入口决策门禁

- 阶段：R13-C3-M0
- 状态：已冻结；用户于 2026-07-19 批准此最小权限决策
- 前置：[R13-C3 首项受审批管理写入：Scope 受限 Memory Forget 执行契约](r13-c3-approved-memory-forget-execution.md)
- 实施计划：[R13-C3 Scope 受限 Memory Forget 实施计划](../plans/2026-07-19-r13-c3-memory-forget-implementation-plan.md)
- 关联 ADR：[ADR-0039：C3-M0 不新增直接 Memory Forget 管理入口](../adr/0039-keep-c3-memory-forget-creation-behind-approved-tool-producer.md)

> C3-M0 的结论是：**不新增控制面创建 Forget 请求的入口。** 已有受审批的 `forget_memory` Tool/Chat
> Producer 是唯一的非空请求创建者；Loopback 控制面只操作已经创建的 Pending Operation。该选择避免在没有安全
> Memory 浏览、目标选择或生产数据授权时，把只读能力变成写权限。

## 1. 三项已冻结决定

| 决策 | 已冻结值 | 明确拒绝 |
| --- | --- | --- |
| 目标引用 | 不创建 `MemoryTargetRef`、`forgetTargetRef` 或任何由控制面签发的 Memory 目标。唯一公开控制引用是既有 22 字符不透明 `operationRef`；它只指向已持久化的 Pending Operation，**不是** Memory ID，也不能创建请求。 | 原始 Memory ID、Hash、Session、Scope、`detailRef`、`historyRef`、cursor 及从它们推导的写引用。 |
| 请求表面 | 不增加新的 `POST`/`DELETE` Route。非空请求仅由搜索解锁后的静态 `forget_memory` Producer 创建；控制面仅保留既有 `GET /api/v1/control/pending-operations/{operationRef}`、`POST .../{operationRef}/resume`、`POST .../{operationRef}/cancel`。 | `/api/v1/control/memory/**`、任意“create forget”端点、body/query 中的 Tool 参数、批量 ID、Scope、Session、Approval 或调用者幂等键。 |
| 数据权限 | Tool 参数只在受控 Producer 中规范化并写入认证加密 Capsule；Scope 只由 Capsule 的 Session 派生；Recovery 只通过窄口 `MemorySoftForgetPort` 写入当前 Scope。自动化只使用临时 Java SQLite/Fake。 | 控制面直接查询或写入 Memory SQLite、Python `memory2.db`、用户/生产数据、真实 Workspace、网络、渠道或 Provider。 |

## 2. 固定流程与审批责任

```text
tool_search -> Deferred forget_memory Tool -> Pending + Approval + Capsule + Anchor
  -> authenticated local operator makes the existing Approval decision
  -> operationRef status | resume | cancel
  -> only approved recovery may reach MemorySoftForgetPort
```

1. `operationRef` 只能在已有 Pending 创建成功后由系统生成；它不能被当成 Tool 输入、Memory 选择器或跨 Scope 查询键。
2. `resume` 不创建 Approval、Capsule、Anchor 或目标；当 Approval 未批准、Anchor 不匹配、已取消/到期或状态终结时，
   它必须零 Invoker。`cancel` 也不能重新获得执行权。
3. Controller 不读取 Capsule、Tool 参数、安全 Ledger 结果或 Memory 数据；成功响应仍只有
   `schemaVersion`、`state`、`updatedAt`。
4. 认证后的本机 operator 只能在既有 Inbox 内做 Approval 决定，并对既有 `operationRef` 执行固定动作；系统、模型、
   Plugin、MCP 或 Worker 不能自动批准、自动 Resume 或新建操作。

## 3. 激活与默认拒绝

所有既有 `DISABLED`、Servlet、Loopback、Bearer、Approval Inbox、Java Native Memory、Capsule Key 和
`APPROVAL_REQUIRED` 前置仍不变。它们仅控制已存在的受控路径，**不**因为本 M0 决策而映射新 Controller、
读取新数据源或开启真实数据执行。

未知 path、method、body、query、引用格式或 actor 一律沿既有 Loopback Guard/Controller 规则 Fail Closed。C2-B 的
只读 `detailRef`、`historyRef` 和 cursor 无论是否有效，均不能用于 C3 请求创建或恢复。

## 4. 验收与未来变更

本 M0 不新增运行时代码、Fixture 或公开协议，故复用已版本化的验证资产：

- `tools/memory-forget-pending-producer-v1.json`：只有受控 Producer 可创建非空 Pending；
- `tools/pending-operation-v1.json`：Capsule、Approval、Reservation、Ledger、Anchor 与状态优先级；
- `control-plane/pending-recovery-control-v1.json`：24 个 `operationRef`/Resume/Cancel/Status Case，固定无 body/query、
  默认未映射、零重放和安全状态投影。

任何未来“直接从控制面创建 Forget 请求”的需求都推翻本 M0 的第二项决定，必须先建立新的 C3 子阶段，并同时获得：

1. 独立、版本化的目标 Ref 生命周期 Contract；
2. 唯一请求 path/method/body/幂等 Contract；
3. Java 数据源、Scope 映射、字段白名单与测试数据边界的书面批准；
4. 新的 RED Fixture、最小 Producer/Controller、失败闭环和三套完整门禁。

在此之前，M1–M6 不启动；本 M0 不授权真实数据、部署、前端、远程访问、Telegram、CLI+Web 或其他写 Capability。
