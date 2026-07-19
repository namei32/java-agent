# R10-P3 上下文超限安全恢复 Contract

- 阶段：R10-P3
- 状态：已实现并验证
- Fixture：`testdata/golden/provider/r10-context-limit-recovery-v1.json`
- ADR：[ADR-0030](../adr/0030-recover-context-limit-before-any-tool-execution.md)
- Python 证据：`agent/core/passive_turn.py` 的 `run_turn`、`_build_attempt_plans` 与 `agent/prompting/budget.py`（基线 `b65a543`）

## 1. 范围

Java 只迁移 Python “遇到上下文超限后以更小 Prompt 重试”的安全子集。它是 Application 内的同步恢复，既不改变
Provider Adapter 的 P0 分类，也不为版本化 Channel 增加自动重投、文本 fallback 或新的 Failure Code。

## 2. 激活与候选

`agent.context-limit-recovery.mode` 只接受严格大写值：

| 值 | 行为 |
| --- | --- |
| `DISABLED` | 默认；只进行原始模型调用。 |
| `SAFE_LOCAL` | 仅允许本 Contract 的非流式、执行 Tool 前恢复。 |

`SAFE_LOCAL` 的候选顺序固定为现有 Java `PromptTrimPlan`：`FULL`、`TRIM_SKILLS_CATALOG`、`TRIM_ACTIVE_SKILLS`、
`TRIM_LONG_TERM_MEMORY`、`TRIM_RETRIEVED_MEMORY`，均先使用当前 `ConversationHistorySelector` 的已选历史；最后在
`TRIM_RETRIEVED_MEMORY` 下将这份已选历史缩为尾部 50% 和 0。候选按 `(trimPlan, historySize)` 去重。当前 User、
`IDENTITY`、`BEHAVIOR_RULES` 与 `SESSION_CONTEXT` 绝不从候选中移除。

## 3. 安全与提交不变量

只有 Provider 已投影为 `ModelContextLimitException`、非流式且本 Turn 的 Tool 执行计数为零时，才能尝试下一个候选。
在调用模型前、首次失败后、重建 Prompt 时或调用 Tool 前发生取消，立即终止且不得发起下一请求。

一旦任一 Tool Call 已执行，后续 `ModelContextLimitException` 直接失败；不重放模型、Tool、Approval、Reservation、
Ledger 或 Side Effect。流式请求、`ModelSafetyRejectedException`、Timeout、通用模型失败和空响应也直接维持既有行为。

候选成功只提交当前 `PersistedTurn` 一次。源历史保持原样：不删除、截断、覆盖、另存 retry trace 或向外投影任何内部
候选信息。所有候选耗尽时保留原始 `ModelContextLimitException` 的固定公开 message 和既有 Channel/HTTP 映射。

## 4. 验收

Java-owned Fixture 固定模式、候选顺序、历史缩减、去重和不可裁剪 Section。生产 `ContextLimitRecoveryPolicy` 必须消费
全部 Case。Application/Bootstrap 测试另覆盖默认零重试、首请求重试成功、候选耗尽、取消、流式零重试、非 Context
失败零重试、Tool 执行后零重试、单次提交和历史不变。

测试只使用 Fake Model、固定 Clock 和内存/临时 SQLite Session；不调用真实 Provider、网络、Telegram、MCP、Workspace
或 Side Effect Capability。实现完成后运行默认、`failure`、`compat` 三套门禁。
