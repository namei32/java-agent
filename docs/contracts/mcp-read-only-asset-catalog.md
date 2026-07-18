# R12-S2 MCP Resources / Prompts 只读目录契约

- 阶段：R12-S2
- 状态：已实现并验证；默认 `DISABLED`
- 契约版本：1
- 日期：2026-07-19
- 前置：[MCP 只读客户端与 Tool Runtime 契约](mcp-client-tool-runtime.md)
- 关联 ADR：[ADR-0021：将 MCP Assets 保持为目录发现而非 Prompt 注入](../adr/0021-keep-mcp-assets-as-catalog-only.md)

## 1. 目标与非目标

本切片补足 R5.1 未覆盖的 MCP `resources/list` 与 `prompts/list` **目录发现**。它只使用既有、静态配置、stdio
且本地受控的 Server；将符合预算的元数据投影为 Java-owned descriptor，以供未来受审计的能力选择器使用。

它不读取 Resource、不会调用 `resources/subscribe`、不会调用 `prompts/get`，不支持 Sampling、Roots、Elicitation、
Tasks、Apps、Streamable HTTP、SSE、OAuth、远程 Server 或动态增删 Server。目录内容、Prompt 文本及其参数绝不
进入模型 Prompt、Conversation、HTTP 响应、日志或错误正文；本切片不注册 Tool，不改变任何 Agent 行为。

## 2. 启用与零资源默认

`agent.mcp.assets.mode` 只能取严格大写值：

| 值 | 行为 |
| --- | --- |
| `DISABLED` | 默认；R5.1 Runtime 即使为 `STATIC_READ_ONLY` 也不声明 Assets Client Capability、不调用列表方法、不创建 Assets Catalog。 |
| `CATALOG_ONLY` | 仅对既有 `STATIC_READ_ONLY` 的本地 stdio Server，在初始化后有界调用 `resources/list`/`prompts/list`；Server 未声明对应 Capability 时稳定为空。 |

`CATALOG_ONLY` 不能绕过 R5.1 的静态配置、受控 Process、Wire/Deadline/取消/关闭或 Stale 边界。`agent.mcp.mode`
不是 `STATIC_READ_ONLY` 时，Assets Mode 必须等价于 `DISABLED`，不读取额外配置也不启动进程。

## 3. 不可信目录投影

每个 descriptor 只含固定 schema version、Server ID、稳定本地名称、公开摘要和可用状态：

- Resource 必须有绝对或自定义 scheme 的 URI、单行 name，description/mimeType 可选；不读取 URI 内容。
- Prompt 必须有单行 name，description 可选；参数只投影 name、`required` 与稳定的公开类型，绝不回显默认值、
  用户值、模型文本或 Server 原始 schema。
- 所有字符串采用严格 UTF-8、Unicode code-point 上限、单行规范化和控制字符拒绝；非法、重复、超限或不可信
  Cursor 只隔离对应条目或 Server，不能扩大访问范围。
- 本地名称由 `mcp_<serverId>__resource_<sha256(uri) 前 16 位>` 和 `mcp_<serverId>__prompt_<normalizedName>` 确定性
  派生；不会泄漏工作目录、命令、环境、Session、Tool、Approval、Capsule 或 Resource 正文。

目录按 `serverId`、kind、localName 稳定排序。Server 通知 `resources/list_changed` 或 `prompts/list_changed` 时，
Assets Catalog 与既有 Tool Catalog 一起变为 `STALE`；不在当前 Runtime 热刷新，不后台轮询或自动重连。

## 4. 有界发现与失败

复用 R5.1 的 `max-list-pages`、`request-timeout`、`max-wire-bytes` 与关闭语义；另固定每 Server 最多 32 Resources、
32 Prompts、每个公开字段最多 512 code points、每个 Prompt 最多 16 个参数。分页 Cursor 不得为空循环、重复、超过
页数或越过条目预算。

一个 Server 的 Assets 发现失败只使其 Assets 目录为空并标记不可用；它不得移除已经投影的 R5.1 Tool、影响普通
聊天或使其他 Server 不可用。包含不支持 Assets Capability 的 Server 是正常空目录，不是启动失败。

## 5. 验收与暂停条件

Java-owned `mcp/read-only-asset-catalog-v1` Fixture 将固定默认零调用、Capability 缺失、分页/排序/稳定命名、
UTF-8/预算/重复/恶意 Cursor 拒绝、Stale、失败隔离、零 Prompt 注入和零 Resource/Prompt 正文泄漏。测试只使用
仓库编译出的本地 Java Reference Server 与临时文件，不能访问网络、真实 MCP Server、Python、Secret 或用户数据。

实现证据（2026-07-19）：13 Case Fixture 由 `McpAssetCatalogContractTest` 消费；本地 Java Reference Server
验证 capability gate、分页、默认零 `resources/list`、每类 32 项上限、Assets 失败不移除既有只读 Tool、以及
`resources/list_changed` Stale。完整 `clean verify`、`-Pfailure verify`、`-Pcompat verify` 已通过。

在新增 `resources/read`、`prompts/get`、模型可调用选择器、任何 Prompt 注入、HTTP Transport 或真实 Server 之前，
必须分别冻结数据边界、提示注入防护、参数/审批/审计与取消 Contract；本契约或其测试均不授予这些权限。
