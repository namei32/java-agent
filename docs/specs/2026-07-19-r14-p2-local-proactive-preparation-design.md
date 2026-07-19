# R14 P2 本地主动候选与 Fake Delivery Preparation 设计

- 状态：P2-A 已实现；P2-B/P2-C 未开始
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

P2-B (future): candidate -> R11 Pending + Approval + encrypted Capsule
P2-C (future): approved reservation -> Fake Delivery Port only
```

P1 的 `ReadOnlyProactiveDecisionRunner` 是只读投影，按设计不保留 Source/Drift 内容。P2-A 不能把其
`PENDING_APPROVAL` 直接当成可发送命令；它必须重新在同一受控调用内执行 Gate、Source、Drift，并把候选仅留在
Application 内存中。因此两者不共享候选对象，也不改变 P1 的公开语义。

`LocalProactiveCandidatePreparer` 位于 `agent-application`，仅依赖已有 Kernel 值对象与 Application Ports。它不
创建配置项、Spring Bean、线程、数据库、Tool、HTTP Route 或 Scheduler 连接。`LocalProactiveCandidate` 不跨模块暴露
正文：公开 API 只能得到安全状态，未来 P2-B Producer 作为同一 Application 包中的受控消费者才能读取候选。

异常一律稳定降格为 `SKIPPED(PROACTIVE_SOURCE_INVALID)` 或 `SKIPPED(DRIFT_READ_ONLY)`；取消始终优先于候选创建。
安全审计沿用 P1 的 hash-only action/code/time 投影，不能记录 Source/Drift 内容。
