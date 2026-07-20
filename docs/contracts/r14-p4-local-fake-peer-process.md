# R14 P4 本地 Fake Peer Process 契约

- 阶段：R14 P4
- 状态：已实现为未接线的本地 Fake Capability；默认仍 `DISABLED`
- 日期：2026-07-19
- 关联 ADR：[ADR-0041](../adr/0041-restrict-r14-p3-p5-to-approved-local-fakes.md)
- 前置：[R14 P0 Peer 边界](r14-proactive-peer-automation-boundaries.md)、[Pending Operation Recovery Capability](pending-operation-recovery-capability.md)

> P4 是 Manifest/预算/状态与 Fake Process Port 的离线演练，不是进程 Launcher、A2A Client、Agent Card 网络服务或 Peer
> Tool。不得执行命令、创建目录、继承环境、打开网络或向渠道推送结果。

## 1. 静态 Manifest 与 Card

唯一允许的 Peer 为 `LOCAL_FAKE`，使用 `peer-local-fake` opaque `peerRef`、固定协议 `local-fake-peer-v1`、版本 1、
固定 1,024 code points 输出预算、5 秒超时和最大一个并发 Task。Manifest/Card 不包含 URL、Host、端口、命令、argv、
工作目录、环境、PID、文件、密钥或任务正文。任何 remote trust、未知字段、大小写变体、超预算值或动态 Card 都以稳定
`PEER_CONTRACT_INVALID` 拒绝。

## 2. 审批与 Task 生命周期

`local_fake_peer_task` 是 `EXTERNAL_SIDE_EFFECT` 风险 Capability。它只接收 static Manifest 引用与固定无正文 Task Kind，
创建独立 Approval、Anchor 与认证 Capsule。状态固定为：

```text
PENDING_APPROVAL -> APPROVED_PENDING_RESUME -> RUNNING -> SUCCEEDED|FAILED|CANCELLED|UNKNOWN
```

只在单次 Reservation、已批准、未过期和认证 Capsule 下调用 `FakePeerProcessPort`。Port 返回的安全终态仅为
`SUCCEEDED`、`FAILED` 或 `CANCELLED`，并受固定输出字符预算和控制字符净化；原始 stdout/stderr、Task 正文和 Process
细节不得出现在 Outcome、审计或日志。异常、审计失败、Ledger/Anchor 失败为 `UNKNOWN` 或 `COMMIT_UNREPORTED`，绝不重放。

## 3. 关闭、取消与恢复

取消、超时、关闭和并发竞争优先于 Fake Port；未开始时零调用。运行中取消只请求 Port 的受限 `cancel(taskRef)`，不启动
真实进程树；端口不确定时转 `UNKNOWN`。没有 Worker、轮询、自动恢复或后台重试；只有显式测试调用 Recovery Coordinator。

## 4. 实现证据、验收与禁止项

`r14-local-fake-peer-process-v1` 固定 15 个 Manifest/Card/预算/输出净化 Fixture 场景。独立的 P4
Pending/Approval/Anchor/AES-GCM Capsule 与 Recovery 只调用注入的 `FakePeerProcessPort`；其测试覆盖未批准、取消、
过期、篡改、终态失败、运行中取消、并发单获胜者、Port/审计/Ledger/Anchor 失败、`UNKNOWN`、
`COMMIT_UNREPORTED` 与零重放。该 Port 仅有测试 Fake；没有 Agent Card 网络服务、A2A Server 或真实进程。

Fixture 覆盖 Manifest/Card、预算、输出净化、未批准、过期、取消、单获胜者、Port/审计/提交失败、`UNKNOWN` 与零重放。
所有测试只使用 Fake Process/Fake A2A response 值，不能调用 `ProcessBuilder`、`Runtime.exec`、Socket、HTTP Client 或文件
系统。P4 不增加 Bootstrap、Route、配置、真实命令或网络授权。
