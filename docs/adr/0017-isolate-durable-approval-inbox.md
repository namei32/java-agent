# ADR-0017：审批收件箱使用独立耐久边界

- 状态：已接受
- 日期：2026-07-18
- 决策：R11 B2a

## 背景

现有审批框架的 `ApprovalPort` 是同步接口；R6.5 `ActiveTurnRegistry` 只保存当前 JVM 活动 Turn，并会在终态或进程关闭时销毁。把待审批记录放入任一对象，会让重启、并发决定和泄露边界不明确，也会把控制面观察状态误作执行事实。

## 决策

使用 `approval-inbox.db` 的独立版本化 SQLite Schema 保存最小审批投影。它由 Application Port 抽象，并只在显式的本地 Loopback 模式下创建。Session Binding、Turn ID 和 Call ID 只以带用途域分隔的 SHA-256 摘要持久化。控制 API 只暴露新的随机 `approvalRef` 和脱敏投影；不暴露 `approvalId`、Fingerprint、参数哈希、幂等键或关联 Turn。

Operator Session 的随机 `actorRef` 仅记录本次本地决定的来源，不能视为账号、角色或授权委托。Inbox 决定不直接驱动 `ApprovalPort` 或 Tool 执行。

## 后果

- 获得可测试的跨重启、原子一次性审批基础，且不会污染 Session、Channel Ledger 或 Memory 的恢复语义。
- B2a 不产生可恢复 Tool 执行；真正的 Pending Operation 仍需要加密/隔离参数胶囊、Ledger 和 Conversation 原子衔接的独立 Contract。
- 需要维护一份新的 Schema、保留策略和受限 API Fixture，但避免在已有数据库中进行高风险耦合迁移。
