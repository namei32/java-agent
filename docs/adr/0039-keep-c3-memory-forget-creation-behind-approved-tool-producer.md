# ADR-0039：C3-M0 不新增直接 Memory Forget 管理入口

- 状态：已接受
- 日期：2026-07-19
- 决策人：用户
- 关联：[R13-C3-M0 Memory Forget 管理入口决策门禁](../contracts/r13-c3-memory-forget-management-ingress-gate.md)

## 背景

R13 已有控制索引和零正文历史详情，但没有安全的 Memory 浏览、目标选择或控制面创建 Pending 的数据契约。
把 `detailRef`、`historyRef`、cursor 或原始 Memory ID 用作写目标，会把 C2 的只读能力升级为未经批准的写权限。

R11-B2c 已存在更窄的创建路径：受控 Deferred `forget_memory` Producer 在当前 Tool/Chat Context 内创建
Approval、加密 Capsule、Pending Operation 与 Session Anchor；Loopback 控制面随后只以 `operationRef` Resume、
Cancel 或查看状态。

## 决策

1. C3-M0 不创建 `MemoryTargetRef`，也不新增控制面 Forget 创建 Route。
2. `operationRef` 保持为既有 Pending Operation 的不透明控制引用；它不是 Memory 目标、Session/Scope 查询键或
   Tool 参数，不能赋予创建或读取 Memory 的权限。
3. 非空 `forget_memory` 仍只能由已有受控 Producer 创建。控制面保持现有的状态、Resume、Cancel 三个固定操作，
   不接受 body/query 中的 Tool 参数或原始标识。
4. 数据继续只在认证 Capsule、当前 Scope 和窄口 `MemorySoftForgetPort` 内流动；M0 不接线控制面到 Memory SQLite，
   也不授权真实数据、网络或渠道。

## 后果

这使 C3 的首项写能力保持可用的本机审批/恢复链，而不在缺少安全目标模型时增加一个表面上方便、实际可越权的管理 API。
若未来需要直接管理入口，必须建立新的目标 Ref、请求和数据权限 Contract，并从 RED Fixture 开始；不能通过扩展
`operationRef` 或 C2 的只读引用来规避该流程。
