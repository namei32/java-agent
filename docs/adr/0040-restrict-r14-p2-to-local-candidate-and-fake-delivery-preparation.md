# ADR-0040：R14-P2 先局限为本地候选与 Fake Delivery Preparation

- 状态：已接受
- 日期：2026-07-19
- 决策人：用户

## 背景

R14-P1 已证明本地 Fake Source 与只读 Drift 可以产生无正文的 `PENDING_APPROVAL` 投影，但它没有 Approval、Capsule、
Ledger、目标或投递权限。直接将该投影连接到 Scheduler、Outbox 或 Channel，会把只读判断错误升级为外部副作用。

## 决策

P2 首先只实现未接线的本地候选准备器。它重新在同一调用内执行 Gate、Fake Source 与只读 Drift，并将净化的 Source
仅保留为 Application 内存候选。P2-B 以后如要创建 Pending，必须复用 R11 的持久 Approval/Capsule/Reservation/Ledger；
P2-C 的 Invoker 只能是 Fake Delivery Port。

不增加 Scheduler/Bootstrap、网络、渠道、Outbox、Receipt、Provider、Memory DML、控制面 API 或新的运行配置。

## 后果

P2-A 可以用版本化 Fixture 验证候选生成与失败短路，而不会使 P1 的无正文投影或 R8 默认关闭语义发生变化。任何真实
Source、真实 Recipient 或 Delivery Channel 都必须另建 Capability Contract 和操作授权。
