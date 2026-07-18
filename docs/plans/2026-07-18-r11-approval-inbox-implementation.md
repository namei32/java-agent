# R11 B2a 本地审批收件箱实施计划

- 状态：B2a 已实现并通过聚焦 default / failure / compat
- 日期：2026-07-18
- 前置：R11 B1 `2012efb` 已验证

## 完成定义

完成 B2a 只表示本机、默认关闭的耐久审批收件箱可被严格测试；它不表示能够执行任何副作用或恢复任何 Tool Turn。

## 顺序

1. **RED：Contract Fixture。** 新增 `testdata/golden/tools/approval-inbox-v1.json` 与 Manifest，覆盖 28 个 Java-owned 场景：Disabled、模式组合、创建、重启、严格 JSON、Actor、不透明 Ref、并发决定、到期、终态、事务失败、审计和零 Invoker。
2. **GREEN：Kernel/Application。** 增加受限 Inbox 模型、状态机、随机 Ref Generator 和 Port；单元测试先锁定状态机与无敏感 `toString`。
3. **GREEN：SQLite。** 独立 Schema/Repository、版本校验、事务 CAS、惰性过期和重启测试；无 Arguments 列。
4. **GREEN：Loopback API。** 新配置默认 Disabled；复用既有 Guard/Session/审计，新增列表和单决定端点；严格 DTO 与错误映射。
5. **Failure/Compat。** 在 `failure` 验证写入/事务/并发失败 Fail Closed、未调用 Invoker；在 `compat` 消费全部 Fixture。
6. **文档与门禁。** 更新能力矩阵、路线图、Fixture 索引和 R11 总计划；运行 B2a 聚焦 default/failure/compat。R11 后续切片完成后才跑 Reactor 三套全门禁。

## 冻结项

- 不修改 `ApprovalPort`、`SideEffectBatchCoordinator` 或 `DenyAllApprovalPort` 的可观察语义。
- 不新增真实 Tool、外部网络、文件写入、Shell、消息发送、Dashboard 前端、CLI+Web 或远程控制面。
- 不把公开 Summary 作为任何执行输入，也不把 `APPROVED` 解释为执行授权。
