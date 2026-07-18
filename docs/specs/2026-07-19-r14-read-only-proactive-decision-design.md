# R14 P1 本地只读主动决策设计

- 状态：已实现；未接线
- 日期：2026-07-19
- Contract：[R14 P1 本地只读主动决策契约](../contracts/r14-read-only-proactive-decision.md)
- ADR：[ADR-0027](../adr/0027-freeze-r14-proactive-peer-automation-boundaries.md)

~~~text
ProactiveJobLease + TurnCancellation
                 |
                 v
             ProactiveGate
        skip / requested only
                 |
                 v
      FixedLocalProactiveSource
       exists? (body is discarded)
                 |
                 v
          ReadOnlyDriftRunner
       clean / detected / cancelled
                 |
                 v
 ReadOnlyProactiveDecision
   SKIPPED(code) | PENDING_APPROVAL | CANCELLED
                 |
                 +-- ProactiveDeliveryBoundary: never authorized
                 +-- Memory mutation count: always zero
                 +-- safe audit: hash/action/code/time only
~~~

FixedLocalProactiveSource 和 Runner 都位于 agent-application，但没有由 ApplicationConfiguration 或
ProactiveRuntime 装配。这样测试可注入 Fake Source、Fake Drift、固定 Clock 和取消信号，生产 Runtime 则没有到
该对象的引用，更不可能隐式访问外部资源。

P1 把检测到的 Drift 降格为 PENDING_APPROVAL 而非真正审批：没有 request ID、持久化、外部副作用或恢复路径。这使
P2/P3/P4 将来必须显式接入 R11-B2c Capability，而不会把“已评估”误写为“已授权”。P0 的 Peer 值对象也没有在
P1 使用，防止本地 Fake Source 演变为隐式 Peer 启动。
