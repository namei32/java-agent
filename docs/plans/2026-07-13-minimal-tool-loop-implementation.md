# 最小 Tool Loop 实施计划

- 状态：实施中
- 当前执行状态：Task T3 已完成，下一步为 Task T4
- 日期：2026-07-13
- Spec：[最小 Tool Loop 设计](../specs/2026-07-13-minimal-tool-loop-design.md)
- Contract：[核心消息、生命周期与 Tool 契约](../contracts/core-message-lifecycle-tool.md)

## Task T1：Contract、Spec 与计划（已完成）

- 盘点 Python Provider、Tool Runtime、Registry、Passive Turn 和 Lifecycle Event。
- 固定核心消息、Tool、Lifecycle、迭代、失败、并发和提交语义。
- 明确第一阶段只读范围以及相对 Python 的批准差异。

验收：文档自审，不运行 Maven。

## Task T2：Tool Golden（已完成）

- 扩展 Python 生成器，生成 Tool Message Python Reference。
- 增加最小循环 Migration Contract 夹具。
- 更新 Manifest、Golden 规范和文档索引。
- 在 Java Application 建立兼容测试，只验证夹具与当前协议投影，不人为制造生产 RED。

聚焦验收：生成器重复运行字节一致；Tool Golden 测试全部通过。

实施结果：

- Python Reference 固化 2 个 Tool Message Case，直接调用生产 `build_tool_schemas`、`append_assistant_tool_calls` 和 `append_tool_result`。
- Migration Contract 固化 7 个最小循环 Case，覆盖直接回答、单/多工具、未知工具、工具异常、非法响应和迭代上限。
- Manifest 增加 4 个 Python Tool/Lifecycle 参考文件 Hash，以及 2 个新夹具 Hash。
- 生成器连续执行两次，Tool Message、Minimal Loop 和 Manifest 的 SHA-256 分别保持一致。
- 聚焦验收实际执行 Manifest 1 Test、Tool Golden 2 Tests，全部通过。

## Task T3：Kernel Tool 与 Lifecycle 协议（已完成）

- 增加 Tool Definition、Call、Result、Status、Risk。
- 扩展模型消息、请求和响应。
- 增加生命周期事件与 Observer Port。

聚焦验收：Kernel Tool Contract 测试一次 RED、一次 GREEN。

实施结果：

- 增加纯 JDK Tool Definition、Risk、Call、Result 和预留状态，第一阶段在构造边界拒绝非只读工具。
- 增加递归不可变 JSON Object 快照，避免调用参数或 Schema 在构造后被外部修改。
- 模型消息扩展为普通文本、Assistant Tool Call 和关联 Tool Result，同时保持原有文本便利构造。
- 模型请求携带 Tool Definition，模型响应携带 Tool Call 并拒绝空响应和重复 Call ID。
- 增加安全 Lifecycle Event 与 Observer Port，事件类型不承载消息、参数、结果或异常正文。
- T3 有效 RED 为协议类型缺失；聚焦 GREEN 实际执行 4 Tests，全部通过。

## Task T4：Application 最小 Tool Loop

- 实现只读 Tool Registry 和有界 Tool Loop。
- 实现顺序执行、未知工具、异常恢复和生命周期序列。
- 接入 ChatService，保持最终轮次原子提交。

聚焦验收：Application Tool Loop 测试一次 RED、一次 GREEN。

## Task T5：Spring AI Adapter 与 Bootstrap

- 映射工具定义、Assistant Tool Call 和 Tool Result。
- 解析 Arguments JSON Object，不让 Spring AI 执行真实工具。
- 增加 `current_time`、最大迭代配置和依赖装配。
- 保持公开 HTTP 与 SQLite Schema 不变。

聚焦验收：Adapter/Bootstrap 相关测试一次 RED、一次 GREEN。

## Task T6：文档与阶段门禁

- 更新 README、运行手册、Roadmap、能力矩阵和 Golden 规范。
- 执行格式、默认、失败和兼容门禁。
- 检查架构边界、Secret、Golden Hash、Workspace/SQLite 产物和 Git Diff。

阶段门禁：

```bash
./mvnw spotless:check
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

真实模型 Smoke 不属于自动门禁，不得在无授权情况下运行。
