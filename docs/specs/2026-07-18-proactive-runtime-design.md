# R8 主动运行时、Drift 与 Subagent 设计

## 1. 分层与端口

Kernel 定义 `ScheduledJob`、`ProactiveDecision`、`JobLease`、`SubagentRequest/Result`、预算和稳定码；
Application 提供 Scheduler、Gate、恢复和父子取消；SQLite Adapter 保存状态；Bootstrap 只装配默认关闭 Properties、
单一调度线程与 NoOp Delivery。Plugin Runtime 只能接收完成后的安全 Tap。

## 2. 正确性

取 Job、建立 Lease、开始执行、提交终态和安排下次周期运行是显式事务边界。执行器在事务外运行；提交前 Lease
必须仍属于当前 worker。若 Lease 丢失，结果不 Delivery，不覆盖新 owner。每个 idempotency key 的终态
delivery intent 最多建立一次；无法证明的执行保留 `UNKNOWN` 计数，不自动重跑。

## 3. 默认关闭与关闭顺序

Disabled 返回 NoOp Scheduler，连数据库路径也不解析。Enabled 关闭依次停止 Claim、取消活跃父 Job、传播给
Subagent、在一个 Deadline 内等待、关闭 SQLite；超时只审计，不强杀线程或生成假终态。

## 4. TDD 任务

A1 Kernel/Fixture；A2 SQLite Schema/Claim/Recovery；A3 Scheduler Gate/Dispatch/Cancel；A4 Proactive
Decision 与无 Delivery 默认；A5 Drift 只读与 Subagent 预算；A6 Spring 接线及 `failure`/`compat` 验收。
