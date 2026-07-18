# ADR-0026：将记忆召回限制为当前 Scope 的只读 Deferred Tool（提议）

- 状态：提议；不能作为生产实现授权
- 日期：2026-07-19
- 决策范围：R12-S5 受限 `recall_memory` 替代

## 背景

Python `recall_memory` 由动态 Memory Engine Profile 定义，能够返回 evidence/source-ref/trace，并支持 HyDE、关键词、
时间线和不同 Scope。Java R4.2 刻意采用从空库开始的 Java-native Session Scope，且已批准契约明确排除了模型 Memory
Tool。直接复用 Python Tool 名称/Schema 或把 Prompt Retrieval 暴露给模型，会错误承诺不存在的证据可追溯性、时间线和
跨 Scope 行为，并可能把原始 Scope 扩散到 Tool 参数。

## 拟议决策

如获用户批准，仅添加默认关闭、三重 Mode、当前 Scope 限定的 Deferred `recall_memory`。它使用现有 Java-native
Embedding/cosine/Hotness 检索，但只投影 opaque ID、Java MemoryType、正文和有界 score；它不支持 Python intent、
time filter、source evidence、dynamic Engine Profile 或跨 Scope。Scope 通过显式 per-Turn Context 绑定，普通 Tool/
Plugin/MCP/模型参数都不可见。

## 后果

这是一项受控安全替代，而非 Python 对齐声明。它需要新的 Fixture、Fake Embedding/SQLite 验收、默认关闭与三套阶段门禁；
R4.2 Contract 只有在用户批准该 ADR/Contract 后才可由新切片补充。`memorize`、`forget_memory`、自动写入和 Optimizer
仍分别需要独立的副作用/数据 Contract。
