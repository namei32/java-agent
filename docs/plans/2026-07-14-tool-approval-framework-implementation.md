# Tool Approval Framework 实施计划

- 状态：已批准
- 批准日期：2026-07-14
- 当前执行状态：Task A0、A1 已完成；Task A2 至 A9 待实施
- 日期：2026-07-14
- 阶段：R3.2
- Contract：[Tool 审批、副作用、幂等与沙箱安全契约](../contracts/tool-approval-side-effect-safety.md)
- Spec：[Tool Approval Framework 设计](../specs/2026-07-14-tool-approval-framework-design.md)

> 本计划已获准从 Task A1 开始实施。批准范围不包含真实副作用工具、Approval Channel、生产 Durable Ledger、SQLite Schema 或部署启用。

## 1. 交付范围

本计划只交付：

- 纯 JDK Approval/Side Effect 协议类型。
- Application 审批 Gate、整批协调、指纹与幂等 Port。
- 测试用 Fake Approval Port、Fake Ledger 和 Fake Side Effect Tool。
- 生产 Deny All 装配和 `APPROVAL_REQUIRED` Fail Closed 模式。
- Approval/Side Effect 生命周期与 Golden。

本计划不交付：

- 真实文件、Shell、Web、消息或记忆工具。
- Approval HTTP/UI/Channel。
- Durable Pending Turn、生产 Ledger 或 SQLite Schema 变化。
- 自动重试、补偿、后台任务或并行 Side Effect。

## 2. 分支与提交策略

1. 当前 `docs/approval-side-effect-contract` 只用于评审并批准文档。
2. Contract 批准后，从最新 `main` 创建 `feat/tool-approval-framework`。
3. 每个行为任务保持一次有效 RED、一次相同命令 GREEN。
4. Task A7 的 Golden 验收不人为破坏生产代码制造 RED。
5. 阶段门禁前不例行重复完整 Reactor、Failure 或 Compat。

## Task A0：契约评审与批准

状态：已完成。

2026-07-14 已明确批准：

- R3.2 只实现默认拒绝框架，不实现真实 Side Effect。
- 接受 `APPROVAL_REQUIRED` 新模式。
- 接受四类新增 Lifecycle Event。
- 接受混合批次任一未批准时整批零执行。
- 接受副作用成功、后续 Turn 失败时只保留 Ledger/Audit、不提交 Conversation。
- 接受真实 Approval Channel 与 Durable Ledger 延后。

已完成批准动作：

- Contract 已标记 `已批准`，记录批准日期和版本。
- [核心消息、生命周期与 Tool 契约](../contracts/core-message-lifecycle-tool.md)已升级为版本 2，激活 `DENIED/SKIPPED` 与新增事件。
- Spec 和本 Plan 已标记为已批准、未实施。

验收：文档链接有效、术语一致、没有代码或运行配置变化。

## Task A1：Kernel Approval 与 Side Effect 协议

状态：已完成。

验证证据（2026-07-14）：计划命令先因协议类型与生命周期事件缺失编译失败，随后执行 3 个测试并全部通过。Kernel 生产代码只使用 JDK 类型。

目标：新增不可变协议类型和领域不变量，不引入框架依赖。

计划文件：

- `agent-kernel/.../approval/ApprovalRequest.java`
- `agent-kernel/.../approval/ApprovalDecision.java`
- `agent-kernel/.../approval/ApprovalDecisionStatus.java`
- `agent-kernel/.../approval/ApprovalState.java`
- `agent-kernel/.../tool/SideEffectExecutionState.java`
- `agent-kernel/.../lifecycle/TurnEventType.java`
- `agent-kernel/.../lifecycle/TurnLifecycleEvent.java`
- `agent-kernel/.../approval/ApprovalContractTest.java`

RED 必须证明：

- 空 ID、空 Tool、非法时间范围、`expiresAt <= issuedAt` 被拒绝。
- Decision 与 Request 的 ID/Fingerprint 不匹配不可表示为有效批准。
- Lifecycle 新事件的字段组合和敏感字段边界固定。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-kernel -am \
  -Dtest=ApprovalContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审：Kernel 依赖树仍只有测试依赖；不出现 Spring/Jackson/JDBC/Provider 类型。

## Task A2：Arguments 规范化与审批指纹

目标：实现 `approval-fingerprint-v1`，把批准绑定到不可变操作。

计划文件：

- `agent-application/.../ApprovalFingerprint.java`
- `agent-application/.../CanonicalArguments.java`
- `agent-application/.../ApprovalFingerprintTest.java`

RED Case：

- Object Key 顺序不同但语义相同得到相同 Hash。
- Array 顺序、字符串、数字或任一绑定字段变化得到不同 Fingerprint。
- 简单拼接碰撞输入不产生相同 Fingerprint。
- 重复字段、非有限数字和不稳定值 Fail Closed。
- 测试失败输出不含原始秘密参数。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=ApprovalFingerprintTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

实现限制：不使用 `Map.toString()`、默认 Locale 或平台相关编码；Hash 输入使用长度前缀。

## Task A3：整批审批预检与默认拒绝

目标：在 Invoker 前完成风险计算、全部决定收集和整批零执行。

计划文件：

- `agent-application/.../ApprovalPort.java`
- `agent-application/.../ToolExecutionPolicy.java`
- `agent-application/.../ToolApprovalGate.java`
- `agent-application/.../SideEffectBatchCoordinator.java`
- `agent-application/.../SideEffectBatchCoordinatorTest.java`

RED Case：

- `READ_ONLY` 批次沿用现有执行语义。
- `WRITE/EXTERNAL_SIDE_EFFECT` 未批准、拒绝、过期或错 Fingerprint 时 Invoker 为零次。
- 混合批次中一个未批准，所有调用均零执行并按顺序得到 `DENIED/SKIPPED`。
- Policy 可提升风险但不能降低注册风险。
- Approval Port 抛错、返回空值或迟到决定 Fail Closed。
- 批准后改参、换 Turn、换 Tool 版本或风险变化均失效。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=SideEffectBatchCoordinatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

重构要求：把现有 ToolRegistry 预检与执行边界最小化拆开，不复制 Schema Validator 或预算逻辑。

## Task A4：一次性消费与幂等 Ledger

目标：证明同一批准和同一逻辑操作最多执行一次，并安全处理未知状态。

计划文件：

- `agent-application/.../SideEffectLedger.java`
- `agent-application/.../SideEffectIdentity.java`
- `agent-application/.../InMemorySideEffectLedger.java`（仅测试）
- `agent-application/.../SideEffectIdempotencyTest.java`

RED Case：

- 相同 Approval ID 并发消费只有一个成功。
- 相同 Idempotency Key 重放 `SUCCEEDED/FAILED` 时返回保存结果，Invoker 次数不增加。
- `RUNNING/UNKNOWN` 立即终止，不自动重试。
- 只有能证明未产生副作用的失败才能标记 `FAILED`。
- Invoker 可能已成功但状态保存失败时进入 `UNKNOWN`。
- Ledger 写入失败发生在 Invoker 前时 Invoker 为零次。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=SideEffectIdempotencyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

并发测试使用可控 Barrier/Latch，不使用任意 `sleep`。内存 Ledger 不得成为生产 Bean。

## Task A5：生命周期、取消与提交语义

目标：把 Approval、Side Effect 和 Conversation 提交边界接入现有 Tool Loop。

计划测试：

- `ToolApprovalLifecycleTest`
- 扩展 `ChatServiceTest` 或新增 `SideEffectTurnCommitTest`

RED Case：

- `TOOL_CALL_STARTED -> APPROVAL_REQUESTED -> APPROVAL_RESOLVED` 顺序固定。
- 只有批准并成功 Claim 后才出现 `SIDE_EFFECT_STARTED`。
- 拒绝没有 Side Effect 事件，但可以继续模型并提交最终解释。
- 取消后不执行、不再调用模型、不提交。
- Side Effect 成功后模型失败或 Conversation SQLite 失败：消息不提交，Ledger/Audit 保留。
- Side Effect 状态 `UNKNOWN`：Turn 失败且不再调用模型。
- Arguments、Summary、Actor、Key 和异常正文不进入事件或安全日志。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=ToolApprovalLifecycleTest,SideEffectTurnCommitTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task A6：Bootstrap 模式与 Fail Closed 装配

目标：增加显式 `APPROVAL_REQUIRED` 解析，但生产仍无可执行 Side Effect。

计划修改：

- `ToolRuntimeMode`
- `AgentProperties`
- `ApplicationConfiguration`
- `ToolRuntimeModeTest`
- `ApplicationConfigurationTest`

RED Case：

- `DISABLED` 无 Definition。
- `READ_ONLY` 拒绝注册非只读工具。
- `APPROVAL_REQUIRED` 没有 Approval Port/Ledger/具体 Tool Contract 时启动失败或 Deny All。
- 默认 Bean 是 `DenyAllApprovalPort`。
- 生产上下文不存在 In-Memory Ledger 或 Fake Tool。
- `.env.example` 继续为 `DISABLED`。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-bootstrap -am \
  -Dtest=ApplicationConfigurationTest,ToolRuntimeModeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本任务不得增加 Controller、Endpoint、SQLite 表或真实 Provider 调用。

## Task A7：Approval Golden 与 Compat

目标：固定 Python 风险/拒绝共同投影和 Java 安全差异。

计划资产：

- `testdata/golden/tools/approval-side-effects.json`
- `testdata/golden/manifest.json`
- `tools/golden/generate.py`
- `ToolApprovalGoldenTest`

要求：

- 使用 Python Commit `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef` 的 Registry/Hook 生产 helper 生成 Reference。
- 审批指纹、一次性消费和 Ledger Case 标记为 `migration-contract`。
- 连续生成两次，Fixture 和 Manifest Hash 完全相同。
- 不调用真实文件、Shell、网络、消息或 Workspace。

聚焦验收：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=ToolApprovalGoldenTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本任务是兼容/验收资产，不人为破坏实现制造 RED。

## Task A8：Failure Profile 与文档同步

目标：确保关键失败路径被独立 Profile 选择，并同步当前事实。

要求：

- 审批错配、过期、取消、Ledger 故障、并发消费和 `UNKNOWN` 测试标记 `failure`。
- 更新 Contract 实施状态、Spec、Plan、Roadmap、能力矩阵和本地运行手册。
- 运行手册必须明确 Framework 不等于真实审批可用，生产仍 Deny All。
- 不增加真实 Approval Channel 的伪操作说明。

聚焦 Profile 选择检查：先用 Surefire/Failsafe 报告确认新增失败测试实际被选中；完整 `-Pfailure` 留到 Task A9 阶段门禁。

## Task A9：阶段门禁、自审与提交

按顺序执行一次：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
./mvnw --batch-mode --no-transfer-progress -Pcompat verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
```

随后执行：

- 全 Reactor 禁止依赖扫描。
- Secret、真实 `.env/config.toml`、SQLite、日志和 Workspace 跟踪扫描。
- `git diff --check`、架构边界、错误正文和测试选择器自审。
- 生产 Bean 列表确认：没有 Side Effect Tool、Approval API、In-Memory Ledger 或 Fake。
- `AGENT_TOOL_MODE=DISABLED` 的模板与本地部署检查。

不执行 `real-model-smoke`：本计划不改变 Provider 协议，也没有真实 Side Effect Tool。未来具体 Tool Smoke 必须重新获得网络、费用和外部变更授权。

## 3. 完成定义

R3.2 Framework 只有同时满足以下条件才可标记完成：

- Contract 与核心 Tool Contract 已批准并版本一致。
- Task A1–A8 均有符合 TDD 规则的证据。
- Task A9 所有门禁通过且测试数记录准确。
- 审查无未解决 Critical/Important 问题。
- 生产始终 Fail Closed，没有真实副作用执行面。
- 文档明确列出后续 Approval Channel、Durable Ledger 和首个具体 Tool Contract，不能把 Framework 描述为可用的人类审批产品。
