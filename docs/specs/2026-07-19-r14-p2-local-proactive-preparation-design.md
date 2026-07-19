# R14 P2 本地主动候选与 Fake Delivery Preparation 设计

- 状态：P2-A 至 P2-C 已实现并验证；仍未接线
- 日期：2026-07-19
- Contract：[R14 P2 本地主动候选与 Fake Delivery Preparation 契约](../contracts/r14-p2-local-proactive-preparation.md)

```text
ProactiveJobLease + TurnCancellation
                |
                v
         ProactiveGate
                |
                v
      FixedLocalProactiveSource
                |
                v
        ReadOnlyDriftRunner
                |
                v
   LocalProactiveCandidatePreparer
                |
                +-- CANDIDATE_READY (private, redacted candidate)
                +-- SKIPPED(stable code) | CANCELLED

  P2-B: one claimed candidate -> Pending + Approval + Anchor + encrypted Capsule
                |
  P2-C: approved single Reservation -> injected Fake Delivery Port only
```

P1 的 `ReadOnlyProactiveDecisionRunner` 是只读投影，按设计不保留 Source/Drift 内容。P2-A 不能把其
`PENDING_APPROVAL` 直接当成可发送命令；它必须重新在同一受控调用内执行 Gate、Source、Drift，并把候选仅留在
Application 内存中。因此两者不共享候选对象，也不改变 P1 的公开语义。

`LocalProactiveCandidatePreparer` 位于 `agent-application`，仅依赖已有 Kernel 值对象与 Application Ports。它不
创建配置项、Spring Bean、线程、数据库、Tool、HTTP Route 或 Scheduler 连接。`LocalProactiveCandidate` 不跨模块暴露
正文：公开 API 只能得到安全状态，未来 P2-B Producer 作为同一 Application 包中的受控消费者才能读取候选。

异常一律稳定降格为 `SKIPPED(PROACTIVE_SOURCE_INVALID)` 或 `SKIPPED(DRIFT_READ_ONLY)`；取消始终优先于候选创建。
安全审计沿用 P1 的 hash-only action/code/time 投影，不能记录 Source/Drift 内容。

P2-B/P2-C 不会把 `ProactiveJobLease` 伪装成 Chat Session，也不会复用 Memory Forget 的 sequence Anchor。它们使用
专用的 job/target-hash Anchor，并以 AES-GCM AAD 绑定 operation ref、Approval Fingerprint 与 Anchor。Producer 会原子
claim 候选、重查 Lease；Store 创建失败才允许安全释放重试。Recovery 仅将认证 Capsule、已批准 Reservation 与 Fake Port
串联；Port、审计或写入不确定时转为不可重放的 `UNKNOWN`，Anchor 提交未报告时为 `COMMIT_UNREPORTED`。两者都位于
`agent-application` 同包，只依赖注入的 Fake Store、Cipher 与 Fake Delivery Port；没有 Spring Bean、SQLite 实现、
Scheduler 调用或 HTTP 表面。
