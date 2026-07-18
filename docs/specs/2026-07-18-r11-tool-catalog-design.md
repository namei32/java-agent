# R11 B1 Tool Catalog 设计

- 状态：已实现并验证
- 日期：2026-07-18
- Contract：[Tool Catalog 与 Capability 治理契约](../contracts/tool-catalog-capability-governance.md)
- ADR：[ADR-0016：采用 Turn Scoped Tool Catalog](../adr/0016-use-turn-scoped-tool-catalog.md)

## 1. 决策

在 Application 层引入不可变 Catalog 注册模型与每个 Turn 独立的可见性对象。Kernel `Tool` 协议、现有 `ToolDefinition`、风险枚举和执行端口保持不变；Tool Loop 在每次模型请求前读取当前 Visibility，在 `tool_search` 返回后才扩大下一次请求的 Schema 集合。

这避免将模型可变状态放进 Spring Singleton、ThreadLocal 或 MCP Adapter，也避免把“搜索到”误当成“通过审批”。

## 2. 组件

| 组件 | 责任 |
| --- | --- |
| `ToolCatalogEntry` | 把 `Tool` 与不可变可见性、来源和搜索元数据绑定。 |
| `ToolCatalog` | 校验重复/保留名称，提供确定性的搜索和初始可见项。 |
| `ToolCatalogSession` | 单 Turn 的已解锁集合；只允许 Catalog 命中的 deferred 名称进入。 |
| `ToolRegistry` | 保留 Schema/执行/预算职责，按 Session 返回 definitions，并在保留调用上执行搜索。 |
| `ToolLoop` | 生命周期内创建 Session、每轮投放当前 definitions，并把 Session 传给 Coordinator。 |
| `SideEffectBatchCoordinator` | 在不改变 Gate/Ledger 语义的前提下，把同一 Session 传入 Registry 执行。 |

## 3. 输入、输出与隔离

`tool_search` 是一个 Runtime 内建只读调用，不会进入 Tool Adapter。它只能查询已成功注册的 Catalog；搜索过程不加载 MCP、不开进程、不读文件，也不发网络请求。结果以 `ToolResult.success` 的稳定 JSON 回送模型，解锁操作和结果构造是同一原子步骤。

Tool Loop 对一个模型响应先按该响应开始时的 Visibility 做预检。因此模型不能在同一批次内先搜索、再调用隐藏工具；下一次模型请求取得新 Schema 后才可调用。结果顺序和 Tool Result 回放继续使用既有消息 Contract。

## 4. Bootstrap 与兼容策略

既有 `List<Tool>` 构造器保留为“全部 `ALWAYS_ON`”兼容路径。Bootstrap 改为构造 `ToolCatalog`：内置 `current_time` 常驻；静态 MCP 条目可显式标记为 deferred 并保留经净化的服务器 ID。没有 deferred 项时不向模型发送 `tool_search`，所以默认无 MCP/无 Tool 的部署行为不变。

R5.1 的静态 stdio、只读 Schema 投影、取消、重连和关闭生命周期不修改。R11 B1 不增加 MCP 配置字段、动态服务器管理或远程连接。

## 5. 验收与失败注入

先用 `ToolCatalogTest` 固定 Catalog 不变量、CJK/英文匹配、排序、筛选和 Session 重置；再用 `ToolCatalogLoopTest` 验证生产 `ToolLoop` 的两次 Model Request 可见性与同批次隔离；最后用 `ToolCatalogGoldenTest` 消费 Fixture。失败测试标记 `failure`，包括非法保留名、隐藏调用、取消、模式拒绝与结果泄漏。

本 B1 完成后只运行聚焦测试；R11 的默认、`failure`、`compat` 全门禁留到 B 系列全部完成时统一执行，除非改动触及既有阶段门禁。
