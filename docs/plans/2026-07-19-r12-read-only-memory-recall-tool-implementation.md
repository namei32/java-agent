# R12-S5 当前 Scope 只读记忆召回 Tool 实施计划

- 状态：完成；`clean verify`、`-Pfailure verify`、`-Pcompat verify` 已通过
- 前置：[R12-S5 Contract](../contracts/read-only-memory-recall-tool.md)

1. **M0 审批（完成）。** 用户明确批准此受限替代及 ADR-0026；确认仍不授权真实 Embedding、Python `memory2.db`、跨 Scope、
   `memorize`/`forget_memory`、Optimizer 或部署启用。
2. **M1 Fixture/Kernel RED → GREEN（完成）。** 固定 24 Case `read-only-memory-recall-v1` Fixture、Manifest、严格 Mode/Schema、
   结果投影与稳定错误码；只新增不可变 Query/Hit 值对象和 Port，不改 Prompt Retrieval 输出。
3. **M2 Query Service RED → GREEN（完成）。** 以 Fake Embedding 验证当前 Scope、模型/维度、cosine/Hotness
   稳定排序、类型过滤、候选上限、12k code-point 预算、零 DML 与安全失败。
4. **M3 Context/Tool/Catalog RED → GREEN（完成）。** 将新的 opaque Scope 显式携带到受信 ContextualTool；验证 Deferred 解锁、
   普通 Tool 无 Context、无效参数/取消/Embedding/Store 失败稳定投影。
5. **M4 Bootstrap RED → GREEN（完成）。** 严格 Properties、三重 Mode、Disabled 零 I/O、Result Budget 和 Fake Model 的
   `tool_search → recall_memory → final` 纵向验收；绝不调用真实 Provider。
6. **M5 Gate/Docs（完成）。** 已审查数据投影和默认关闭；Spotless、`clean verify`、`-Pfailure verify`、
   `-Pcompat verify` 均通过，并回写能力矩阵、Roadmap、README 和运行手册。

禁止：实现任何写入/强化、自动记忆、删除、审批恢复、真实网络/费用、Python 数据读取、跨 Scope、时间线、关键词/RRF、
HyDE、远程 Vector Store、CLI+Web 或前端。
