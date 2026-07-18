# ADR-0019：在恢复前冻结 Pending Operation 的 Session Anchor

- 状态：已采纳，部分实施；Anchor 持久化与无执行条件提交已完成，恢复编排仍冻结
- 日期：2026-07-19
- 决策：R11 B2b / O6 Recovery Capability

## 背景

R11 B2b 已有加密参数胶囊、一次性 Approval/Reservation、耐久 Ledger 状态和 `sessions.db` 的
`appendTurnIfNextSequence`。但该 Session Port 只能原子追加完整 `user + assistant` Turn；待审批原 Turn 已经
以安全 `PENDING_APPROVAL` 投影结束，恢复时不能重新生成或把原始用户/模型消息塞进 Capsule。

如果此时把 `RESERVED` 直接接到 Invoker，会出现两种不可接受的选择：要么在没有可提交 Conversation Anchor
时执行副作用，要么在 Capsule 中保存原始消息。这两种都违背 B2b 的恢复安全边界。

## 决策

在完成版本化的 Pending Session Anchor 前，不实现 Resume/Cancel API、后台恢复 Worker、Tool Invoker 接线或
任何可执行 Capability。后续 Anchor Contract 必须明确：

1. 初始 Turn 的安全 `PENDING_APPROVAL` 投影如何以单事务持久化，不含 Arguments、Fingerprint、ID、异常或密钥；
2. 它与 `operationRef`、原始 Session、初始和恢复的 Sequence 怎样绑定，而公开接口只见不透明 Ref；
3. 恢复 Assistant 结果用何种受限的条件追加语义提交，避免重写 User/Assistant 历史；
4. 新 Turn、取消、到期、结果成功、`UNKNOWN` 和 `COMMIT_UNREPORTED` 的优先级；
5. `sessions.db` 与 `approval-inbox.db` 不存在全局事务时，崩溃恢复如何保持副作用最多一次、Conversation 最多一次。

Anchor 的任何原文都不得进入 `approval-inbox.db` Capsule、Approval Inbox、Lifecycle、普通日志、公开 API 或
Fixture Expected 字段。具体 Tool Capability 仍需在 Anchor、显式认证的本机 Resume/Cancel、Sandbox、Key 和
测试专用 Fake Invoker 演练已完成；生产恢复编排、真实 Capability、Key 与任何副作用 Tool 仍须单独批准。

## 后果

- R11 不会因“已有 Ledger”而错误宣称可以批准后执行；生产继续 `AGENT_TOOL_MODE=DISABLED`。
- 需要增加新的版本化 Fixture、Session Port 和 SQLite 测试，而不能复用 `appendTurn` 伪装为恢复提交。
- 完整 R11 的退出门禁延后，但耐久状态机仍可独立、无执行地验证。
