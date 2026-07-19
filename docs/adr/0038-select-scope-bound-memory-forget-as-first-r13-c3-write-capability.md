# ADR-0038：选择 Scope 受限 Memory Forget 作为 R13-C3 的首项写 Capability

- 状态：已接受
- 日期：2026-07-19
- 决策人：用户
- 关联：[R13-C3 首项受审批管理写入：Scope 受限 Memory Forget 执行契约](../contracts/r13-c3-approved-memory-forget-execution.md)

## 背景

R13-C2-B 只提供默认拒绝数据接线的零正文历史详情。它不为 Session、Message 或 Memory 的修改建立权限。C3 的
候选包括删除/编辑 Session 或 Message、全局或批量管理 Memory，以及已有的 Scope 受限 `forget_memory`。其中前几类
需要新的目标引用、保留、恢复和数据授权；`message_push`、文件、Shell 与网络写入还会引入外部副作用。

R11-B2c 已为 `forget_memory` 冻结并实现最小的本地审批链：当前 Scope 批量软失效、加密 Capsule、Approval、
Reservation、Ledger、Anchor、`UNKNOWN` 和 `COMMIT_UNREPORTED`。它默认关闭，且只在临时 Java SQLite/Fake 环境验证。

## 决策

1. C3 的首项且唯一选中写 Capability 是已有静态 `forget_memory` / `java-memory-forget-v1`。C3 采用其
   `memory-forget-capability-v1` 执行边界，不改名、不创建动态 Capability，也不将物理删除伪装为 Forget。
2. 选择仅覆盖当前 Session Scope 的批量软失效和既有的受审批恢复链。它不授权生产数据、真实网络、Telegram、
   前端、CLI+Web、Worker 或自动 Resume。
3. C2-B 的 `detailRef`、`historyRef`、cursor 和任何原始 Session/Memory 标识都不能成为 C3 写入凭据。C3 本轮
   不增加管理写入 HTTP Route；新的入口必须另行冻结目标引用与请求 Contract。
4. Session/Message 删除或编辑、全局 Memory 管理、`memorize`、Optimizer、消息发送、文件、Shell 与 Web 写入
   均保持未选择、默认拒绝，且各自需要独立 Capability Contract。

## 后果

这让 C3 从一个模糊的“管理写入”阶段收敛为一个已有、可审计、最小权限的 Memory 软失效动作，并避免把 C2 的只读
引用升级成写权限。它不表示 C3 实现完成：若需要新的管理入口，仍必须先通过独立 RED Fixture、最小实现与失败闭环验证。
