# 最小 Tool Loop 实施计划

- 状态：实施中
- 当前执行状态：Task T1 已完成，下一步为 Task T2
- 日期：2026-07-13
- Spec：[最小 Tool Loop 设计](../specs/2026-07-13-minimal-tool-loop-design.md)
- Contract：[核心消息、生命周期与 Tool 契约](../contracts/core-message-lifecycle-tool.md)

## Task T1：Contract、Spec 与计划（已完成）

- 盘点 Python Provider、Tool Runtime、Registry、Passive Turn 和 Lifecycle Event。
- 固定核心消息、Tool、Lifecycle、迭代、失败、并发和提交语义。
- 明确第一阶段只读范围以及相对 Python 的批准差异。

验收：文档自审，不运行 Maven。

## Task T2：Tool Golden

- 扩展 Python 生成器，生成 Tool Message Python Reference。
- 增加最小循环 Migration Contract 夹具。
- 更新 Manifest、Golden 规范和文档索引。
- 在 Java Application 建立兼容测试，只验证夹具与当前协议投影，不人为制造生产 RED。

聚焦验收：生成器重复运行字节一致；Tool Golden 测试全部通过。

## Task T3：Kernel Tool 与 Lifecycle 协议

- 增加 Tool Definition、Call、Result、Status、Risk。
- 扩展模型消息、请求和响应。
- 增加生命周期事件与 Observer Port。

聚焦验收：Kernel Tool Contract 测试一次 RED、一次 GREEN。

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
