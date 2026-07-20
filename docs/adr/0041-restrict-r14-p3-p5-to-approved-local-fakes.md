# ADR-0041：将 R14-P3 至 P5 限制为获批的本地 Fake Capability

- 状态：已接受
- 日期：2026-07-19
- 决策人：用户

## 背景

R14-P0 至 P2 已固定主动任务、候选及 Fake Delivery 的审批恢复边界，但没有自动记忆、Peer 进程或受信 Tool 表面。
直接把候选接到 Java Memory、`ProcessBuilder` 或 Tool Catalog，会绕开逐 Mutation 的审批、恢复和最小权限要求。

## 决策

1. P3 只批准 `proactive_memory_capture`：它将 P2 的 `FIXED_LOCAL` 候选作为加密 Capsule 中的敏感载荷，经单次
   Approval/Reservation 后交给注入的 Fake Java-native Memory Mutation Port。它不调用模型或真实 Embedding，不运行
   Scheduler/Optimizer，也不读取生产数据库。
2. P4 只批准 `local_fake_peer_task`：Manifest、Card、预算、取消、Task 状态和输出净化均以纯值对象及 Fake Process
   Port 验证。不得创建 `ProcessBuilder`、网络连接、工作目录、环境继承或真实 A2A 请求。
3. P5 只在 P3/P4 Contract 已获批准后，把两个**请求** Schema 作为静态 `DEFERRED` Builtin 加入 Turn Scoped Catalog。
   Placeholder 不执行、不开 Pending、不注册 Spring Bean；只有未来逐 Capability 的 Producer/配置 Contract 才能连接它。

## 后果

R14 可以验证自动化副作用的最小安全形状，而不把 Fake 验证误称为 Java 自动记忆、真实 Optimizer、Peer 进程或 Tool
运行授权。真实模型摘要、Embedding 费用、Java SQLite/生产 DML、合并/重分类、Peer 命令、网络、真实渠道与自动重试
仍需新的 Contract 和操作授权。
