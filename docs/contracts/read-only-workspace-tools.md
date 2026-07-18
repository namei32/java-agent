# R11-B3 只读 Workspace Tools 契约

- 阶段：R11-B3
- 状态：已实现并经默认、`failure`、`compat` 门禁验证；默认 `DISABLED`
- 契约版本：1
- 日期：2026-07-19
- Python 证据：`agent/tools/filesystem.py`（基线 `b65a543`）
- 前置：[Tool Catalog 与 Capability 治理契约](tool-catalog-capability-governance.md)、[Tool Runtime 安全契约](tool-runtime-safety.md)
- 关联 ADR：[ADR-0022：以独立显式根目录提供只读 Workspace Tools](../adr/0022-use-explicit-root-for-read-only-workspace-tools.md)

## 1. 目标与非目标

本切片迁移 Python `read_file` 和 `list_dir` 的安全子集：模型可在运维人员显式指定的独立根目录内，按相对路径
读取受预算的 UTF-8 文本或列举一层目录。两个 Tool 均为 `READ_ONLY`、`DEFERRED`，必须先经既有 `tool_search`
解锁才会出现在后续模型请求。

不包含 Python 的图片/多模态内容、宽松解码、绝对路径、`~`、任意进程 cwd、递归列举、文件写入、编辑、创建目录、
Shell、Web、MCP、消息、记忆写入或真实 Workspace 演练。`write_file`、`edit_file`、Shell 和任何外部副作用仍须
逐 Tool Capability、审批、Ledger、Sandbox 和 Smoke Contract。

## 2. 启用、Root 与零 I/O 默认

`agent.workspace-tools.mode` 只能是严格大写值：

| 值 | 行为 |
| --- | --- |
| `DISABLED` | 默认；不读取、校验、创建或枚举 Root，不注册 Tool，也不向模型暴露 Schema。 |
| `READ_ONLY` | 只在 `agent.tools.mode=READ_ONLY` 且显式绝对 `agent.workspace-tools.root` 已存在时注册两个 Deferred Tool。 |

`root` 不得复用 `${agent.workspace}`，不得为符号链接，且启动时必须是可解析的真实目录；不存在、不可读、非目录或
链接均启动失败，绝不创建 Root。部署可将下述输出预算收紧，但不能提高 1MB、400 行、10KB、20K code point、256 项
这些固定上限。所有自动化仅使用临时 Java Root；配置本身不构成真实用户数据访问授权。

## 3. 路径、文件与目录语义

调用只接受非空的 Root-relative POSIX 风格 `path`，不得是绝对路径，不能包含 `.`、`..`、空段、NUL 或控制字符。
每一级目录和最终目标都以 `NOFOLLOW_LINKS` 与真实路径验证；任一符号链接、越界、不可读、非普通文件/目录或 TOCTOU
无法证明时 Fail Closed。模型结果、异常、Lifecycle、HTTP、Channel 与安全日志均不得回显物理 Root、绝对路径、链接
目标、权限细节或原始异常。

`read_file` 只接受普通文本文件，使用严格 UTF-8，支持 `offset`（0-based）和 `limit`（1-based positive count）。
输出为 `<1-based line>: <text>` 投影，最多 400 行、10,000 UTF-8 bytes 和 20,000 code points；投影因预算截断时
附加固定独立行 `[TRUNCATED]`，不能把截断视为完整文件。源文件超过 1MB、目录超过预算或目录扫描预算耗尽时返回
`WORKSPACE_BUDGET_EXCEEDED`，不返回部分目录。二进制、非法 UTF-8、首行/源文件过限或图片均返回稳定安全错误，
不尝试替代解码或多模态转换。

`list_dir` 只列一层直接子项，按 Unicode code-point 名称稳定排序，最多 256 项；子项输出为
`<relative name>\tFILE|DIRECTORY`，符号链接、特殊文件、控制字符名称和超限项不进入模型结果。空目录返回稳定空投影。

## 4. Tool 与错误边界

固定名称为 `read_file`、`list_dir`，版本 `workspace-read-only-v1`，风险 `READ_ONLY`，来源 `BUILTIN`。它们不能
由模型参数改变 Root、预算、风险、版本或可见性。`DISABLED` 与全局 Tool Runtime `DISABLED` 时零定义；全局
`APPROVAL_REQUIRED` 不可借此注册，避免把无审批只读 Root 扩展为副作用环境。

稳定失败只区分：`WORKSPACE_TOOL_UNAVAILABLE`、`WORKSPACE_PATH_REJECTED`、`WORKSPACE_NOT_FOUND`、
`WORKSPACE_NOT_TEXT`、`WORKSPACE_BUDGET_EXCEEDED`。它们不含调用路径、Root、内容、异常或操作系统信息。

## 5. 验收与暂停条件

Java-owned `tools/read-only-workspace-v1` Fixture 固定 Mode、路径规范化、预算、稳定码、Definition 与 Disabled 空
Toolset；`WorkspaceReadOnlyToolAdapterTest`、`WorkspaceReadOnlyToolBudgetTest`、`WorkspaceToolBootstrapConfigurationTest`、
`WorkspaceToolChatIntegrationTest` 和 `WorkspaceToolPropertiesTest` 补足 Root 零 I/O、链接逃逸、UTF-8/二进制、行号/
分页/稳定排序、Deferred 可见性及结果脱敏。Adapter 测试只创建临时目录和文件；不运行 Python、真实 Workspace、Shell、
网络、MCP、Secret 或用户数据。

在实现图片、递归搜索、任意路径、写入/编辑、真实 Workspace Smoke、Shell 或 Web 前，必须另行冻结 Contract；
本切片的完整门禁也不授予这些权限。
