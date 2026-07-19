# ADR-0030：仅在任何 Tool 执行前恢复模型上下文超限

- 状态：已接受
- 日期：2026-07-19
- 决策者：Namei Agent Java 迁移维护者
- 关联：[R10-P3 上下文超限恢复 Contract](../contracts/r10-context-limit-recovery.md)、[ADR-0028：Provider 失败分类](0028-classify-provider-rejections-at-adapter-boundary.md)

## 背景

Python `DefaultReasoner.run_turn` 在 `ContentSafetyError` 或 `ContextLengthError` 时，先轮流裁剪 Prompt Section，
再把历史窗口缩至一半和零；成功后还会改写 Python Session 历史。Java P0 已识别 `ModelContextLimitException`，但
目前将它直接投影为 `MODEL_CONTEXT_LIMIT`。直接照搬 Python 重试会有两个不安全之处：Java Tool Loop 的后续模型请求
可能已执行 Tool；流式入口可能已经把内容 Delta 发送给调用方。

## 决策

1. P3 是显式 opt-in，默认 `DISABLED`。`DISABLED` 时只发原请求，不创建重试计划，也不改变既有 P0 失败投影。
2. 只在非流式调用且当前 Turn 尚未执行任何 Tool 时，才可捕获 `ModelContextLimitException` 并发起下一次模型请求。
   已执行 Tool、任何 Side Effect、取消、流式调用、内容安全、Timeout 和未知 Provider 失败一律不重试。
3. 重试候选使用项目已有的确定 `PromptTrimPlan` 顺序；先保持已选择的历史，再在最强 Prompt 裁剪下将历史缩到
   50% 和 0。候选重复时跳过。当前用户消息、身份与行为规则永不裁剪。
4. 每次候选都从未持久化的 Session Snapshot 重建 Prompt；成功后仅追加当前 User/Assistant Turn，不删除、截断或
   重写历史。这样与 Python 的“成功后裁剪 Session”形成有意安全差异。
5. 重试次数、选择的 Prompt Section、历史文本、Provider payload、Tool 参数、Session ID 和重试 trace 都不进入
   HTTP、Channel、日志、SQLite 或 Prompt。耗尽候选后重新抛出原始安全 `ModelContextLimitException`。

## 后果

- Java 获得可证明的无 Tool 重放恢复路径，但不把 P0 的不可重试 Channel 码改成调用方可自行重试的协议。
- Tool/Side Effect 之后的上下文超限仍是稳定失败；后续若要恢复，必须先建立跨 Tool/Approval/Reservation/`UNKNOWN`
  的独立 Contract。
- 本切片不激活 Provider Options、thinking、reasoning、cache usage、真实 Provider 或网络。所有验证使用 Fake Model
  和临时 Session Store。
