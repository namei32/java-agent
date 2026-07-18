# R11-B3 只读 Workspace Tools 设计

- 状态：已实现并验证
- 日期：2026-07-19
- Contract：[只读 Workspace Tools 契约](../contracts/read-only-workspace-tools.md)
- ADR：[ADR-0022](../adr/0022-use-explicit-root-for-read-only-workspace-tools.md)

## 结构

```text
WorkspaceToolProperties (DISABLED | READ_ONLY, explicit root, limits)
        │
        ▼
WorkspacePathResolver ── strict real-path / NOFOLLOW validation
        │
        ├── ReadWorkspaceFileTool
        └── ListWorkspaceDirectoryTool
        │
        ▼
ToolCatalog (BUILTIN, DEFERRED) ── tool_search ── ToolRegistry
```

`adapter-workspace` 持有 `java.nio.file`、UTF-8 解码和文本/目录投影；Kernel 继续只看到既有 `Tool`、
`ToolDefinition`、`ToolResult` 和 `ToolRisk`。Bootstrap 在 `READ_ONLY` 时验证 Root 并创建 Tool；Application
只把已构造 Tool 放入当前既有 Catalog/Runtime，不拥有文件系统 API。

## 数据流与不变量

1. `DISABLED` 返回空 Toolset，既不解析 Root 也不触碰文件系统。
2. `READ_ONLY` 先验证严格 Properties、全局 Tool Mode 与真实 Root；失败在注册前中止。
3. 每次调用再次按段验证相对路径和真实对象；从目录项到读取之间不能证明边界时失败而非读取。
4. Adapter 将安全文本/目录投影映射为稳定 ToolResult；绝不把原始异常、绝对路径或链接目标交给模型。
5. Bootstrap 将两个 Tool 注册为 Deferred Builtin；`tool_search` 只改变当前 Turn Schema 可见性，不改变 Root。

## TDD 分段

S1 Fixture/Mode/Tool 定义、S2 Root/Path Adapter、S3 预算/UTF-8/二进制/错误边界、S4 Bootstrap 默认关闭与
Deferred Catalog、S5 Chat Tool Loop 纵向验证、S6 Fixture Manifest/格式/Secret/Workspace 审计和完整
default/failure/compat 门禁均已完成。补充 RED/GREEN 用例已验证 Unicode code-point 而非 UTF-16 排序。
