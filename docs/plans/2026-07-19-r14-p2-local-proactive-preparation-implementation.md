# R14 P2 本地主动候选与 Fake Delivery Preparation 实施计划

- 状态：P2-A、P2-B、P2-C 已完成并通过阶段门禁
- 日期：2026-07-19
- 适用环境：固定 Clock、Fake Source、Fake Drift、临时 Java SQLite/Fake Pending Store；不使用网络、真实渠道或生产数据

## P2-A：本地候选准备

1. 新增 `proactive/r14-local-proactive-preparation-v1` 版本化 Fixture 和 Manifest 条目。Fixture 覆盖取消、Lease、
   Gate、空/异常 Source、干净/异常 Drift、检测到 Drift、无正文投影与审计脱敏。
2. 编写 `LocalProactiveCandidatePreparerFixtureTest`，先证明新类型缺失或行为不符（RED）。
3. 在 `agent-application` 增加最小的候选、结果和 Preparer；候选只在内存中保留，所有 `toString` 脱敏。
4. 运行同一测试选择器确认 GREEN；自审取消、异常、正文泄漏和模块边界。
5. 更新 Contract、设计、路线图与进度，不接线 Bootstrap。

**结果：** 已新增 12 Case `proactive/r14-local-proactive-preparation-v1` Fixture、Manifest 条目、
`LocalProactiveCandidatePreparer` 和未跨模块暴露正文的候选类型。有效 RED 是生产类型缺失导致 Fixture Test 无法编译；
同一 `LocalProactiveCandidatePreparerFixtureTest` 已 GREEN。尚未运行 P2-B/P2-C 或阶段全门禁。

## P2-B：Fake Delivery Preparation

1. 先冻结 `proactive_prepare_delivery` Capability、Fake Recipient Ref、Canonical Capsule 参数、审批摘要和安全结果 Fixture。
2. RED：空/过期候选、伪造 Recipient、重复、并发、取消和 Store 失败不创建 Pending 或不产生 Invoker。
3. GREEN：唯一受控 Producer 创建独立的 Pending/Approval/Capsule/Anchor；外部只投影安全 `operationRef`。
4. 验证不创建 Outbox、Receipt、Channel Client、Worker 或任何 Bootstrap Route。

**结果：** 已新增 8 Case `proactive/r14-proactive-fake-delivery-preparation-v1` Fixture、Manifest 条目和同包
`ProactiveDeliveryPendingProducer`。它只接受仍有效且仅被原子 claim 一次的内存候选，创建独立的
`proactive_prepare_delivery` Approval、job/target-hash Anchor 和 AES-GCM Capsule；取消、空候选、过期 Lease、重复与
并发均为零或唯一 Pending。Store 创建失败才释放候选供安全重试；Outcome 和 Store 投影不渲染 Source/Recipient。
有效 RED 固定“过期候选仍创建 Pending”的错误，随后同一 `ProactiveDeliveryPendingProducerFixtureTest` GREEN。

## P2-C：Fake Recovery 与失败闭环

1. RED：未批准/取消/过期/Anchor-Capsule 不匹配、并发 Reservation、Fake Invoker 异常、审计或投影提交失败。
2. GREEN：仅单次 Reservation 调用 Fake Delivery Port；异常为 `UNKNOWN`，投影失败为 `COMMIT_UNREPORTED`，均不可重放。
3. 仅以 Fake Pending Store 证明 Pending、Approval、Ledger 和安全恢复状态一致；P2 不实现 SQLite Adapter 或生产 Bean。

**结果：** 已新增同包 `ProactiveDeliveryRecoveryCoordinator`，只可经认证 Capsule、已批准且未过期的单次
Reservation 调用注入的 Fake Delivery Port。6 个默认场景覆盖成功、未批准/取消/过期/密文篡改、Port 失败、Anchor
提交失败、审计失败和并发；1 个 `failure` 场景覆盖成功写入失败后的 `UNKNOWN` 与零重放。它不引入 Scheduler、Worker、
Channel、Outbox、Receipt Adapter、Route 或 Bootstrap 接线。

## 阶段门禁

P2-A 仅运行聚焦 Fixture 测试和格式检查。P2-B/P2-C 均完成后，执行：

```bash
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

随后执行模块边界、Secret、Workspace、默认禁用和工作树审计。任何真实 Source、网络、渠道、Provider、Memory DML、
Scheduler 接线或生产数据需求都停止本计划并请求新的授权。

**阶段验证（2026-07-19）：** `./mvnw clean verify`、`./mvnw -Pfailure verify` 与
`./mvnw -Pcompat verify` 均通过。首次完整 `compat` 扫描曾瞬时报告既有
`JavaNativeMemoryContractTest` classpath 找不到；确认源码与产物存在后，聚焦同类 4 个 Case 通过，随后不改源码的完整
`compat verify` 重跑通过。P2 的 Manifest、P2-B Fixture 与 P2-C 默认/失败测试均由通过的门禁实际消费。
