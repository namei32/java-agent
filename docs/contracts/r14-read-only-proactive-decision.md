# R14 P1 本地只读主动决策契约

- 阶段：R14 P1
- 状态：已实现为未接线的本地 Decision Runner；运行时仍未启用
- 日期：2026-07-19
- ADR：[ADR-0027](../adr/0027-freeze-r14-proactive-peer-automation-boundaries.md)
- Fixture：testdata/golden/proactive/r14-read-only-proactive-decision-v1.json
- 前置：[R14 P0 边界契约](r14-proactive-peer-automation-boundaries.md)、[R8 主动运行时契约](proactive-runtime.md)

## 1. 范围

P1 只把已有的 Gate、P0 FIXED_LOCAL Fake Source 和 ReadOnlyDriftRunner 串成一个同步、注入式的
ReadOnlyProactiveDecisionRunner。它不由 Bootstrap 创建，不启动 Scheduler/线程，不读写数据库，不调用模型、
Provider、MCP、网络、文件、Tool、Peer 或 Memory。它没有配置 Mode、没有 Tool Schema，也没有渠道入口。

它不是 Python proactive_v2 的 Judge/Resolve/Deliver 迁移；尤其不能调用 Python 的模型 Tool Loop、message_push、
ack、状态写入或自动 Memory。

## 2. 输入与顺序

输入是已 Claim 的 ProactiveJobLease、TurnCancellation、ProactiveGate、FixedLocalProactiveSource、已存在的
ReadOnlyDriftRunner 和安全审计 sink。Runner 按以下顺序执行：

~~~text
cancel? -> CANCELLED (zero Gate/Source/Drift)
lease expired? -> SKIPPED(PROACTIVE_LEASE_LOST)
Gate skip? -> SKIPPED(stable Gate code)
FixedLocalSource empty/failure? -> SKIPPED(PROACTIVE_NO_SOURCE|PROACTIVE_SOURCE_INVALID)
ReadOnlyDrift clean/failure? -> SKIPPED(PROACTIVE_NO_DRIFT|DRIFT_READ_ONLY)
ReadOnlyDrift detected -> PENDING_APPROVAL
~~~

Gate、过期 Lease、取消、空 Source 和 Source 失败都短路后续步骤。Source 在返回后发生取消时不运行 Drift；Drift 返回
CANCELLED 或 parent 取消也只返回 CANCELLED。P1 复用 R8 Scheduler 的既有 Lease recovery、崩溃和关闭测试，
但不创建或拥有 Scheduler，因而无法重写其 SQLite 状态。

## 3. 输出、投递与记忆

输出严格为 SKIPPED(code)、PENDING_APPROVAL 或 CANCELLED。它不包含 Source 文本、Drift 摘要、目标、Session、
job ref、消息正文、Approval ref、Ledger ref 或 Provider 内容。

PENDING_APPROVAL 只是投影：allowsExternalDelivery() 和 transportAuthorized() 恒为 false；没有 Approval Request、
Reservation、Capsule、Ledger、Outbox、Receipt 或 Delivery 调用。memoryMutationCount() 恒为 0，且 Runner 根本没有
Memory Port。它不能被当作 R11-B2c、P2 或 P3 的替代。

## 4. Source、Drift 与审计

FixedLocalProactiveSource 只有 next(TurnCancellation)，没有 target、session、URL、路径、MCP、Provider 或网络参数；
返回类型仅为 P0 已净化的 ProactiveSourceItem。Runner 只使用“是否存在”这一事实，绝不向 Drift、输出或审计传递其正文。

审计最多记录 hash-only target、GATE|SOURCE|DRIFT|PROJECTION、可选稳定码和 Clock 时间；不得记录
Source/Drift/Prompt/消息/Session/路径。Audit sink 并不授予执行权。

## 5. 验收

版本 1 Fixture 固定 15 个场景：Gate/冷却/目标 busy、过期 Lease、空/失败/存在 Source、干净/检测/失败 Drift、三个取消
位置、无正文投影及安全审计。ReadOnlyProactiveDecisionContractFixtureTest 以 compat 消费全部场景；
ReadOnlyProactiveDecisionFailureTest 以 failure 覆盖 Source/Drift 异常和 Source 中取消。Fixture Hash 受 Golden
Manifest 保护。P1 不增加 Bootstrap Bean 或新的配置项，默认 agent.proactive.mode=DISABLED 不变。
