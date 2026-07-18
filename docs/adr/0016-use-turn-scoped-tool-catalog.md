# ADR-0016：采用 Turn Scoped Tool Catalog

- 状态：已接受
- 日期：2026-07-18
- 决策者：项目维护者授权的 R11 实施

## 背景

Python Tool Registry 能索引全部工具并通过 `tool_search` 延迟暴露 Schema。Java Registry 只持有静态全量工具，使 Tool 数量增加后每次模型请求都携带全部 Schema，也无法区分发现、可见性、审批和执行。

## 决策

Application 层使用不可变 Catalog 注册元数据；每一个 `ToolLoop` Turn 创建独立的可见性会话。常驻项立即可见，deferred 项只能由保留的只读 `tool_search` 在本 Turn 解锁，且只在随后的模型请求中出现。

Catalog 不存储于 Spring Singleton 的可变会话字段、ThreadLocal、Provider Request、MCP Server 或数据库。Tool Runtime 继续在执行边界完成 Schema、风险、取消、审批和 Ledger 校验。

## 后果

- 能获得 Python 的工具发现共同投影，并限制模型上下文中的 Schema 数量。
- 同一批次不能用搜索绕过初始可见性；Turn 结束必然重置。
- Catalog 元数据必须有稳定校验和 Fixture，因此 Bootstrap/MCP 注册从裸 `List<Tool>` 逐步迁移到显式 Entry。
- 此决策不提供人类审批、持久化、动态 MCP 管理或副作用执行；这些能力必须各自新增 Contract。
