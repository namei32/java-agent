# MCP 只读客户端与 Tool Runtime 契约

- 状态：已批准
- 契约版本：1
- 批准日期：2026-07-15
- 适用阶段：R5.1 MCP 只读客户端纵向切片
- 前置契约：[核心消息、生命周期与 Tool 契约](core-message-lifecycle-tool.md)、[Tool Runtime 安全契约](tool-runtime-safety.md)、[Tool 审批、副作用、幂等与沙箱安全契约](tool-approval-side-effect-safety.md)
- 关联 Spec：[MCP 只读客户端纵向切片设计](../specs/2026-07-15-mcp-read-only-client-design.md)
- 关联 ADR：[ADR-0006：采用官方 MCP Java SDK 并自持有界 stdio Transport](../adr/0006-use-official-mcp-java-sdk.md)

## 1. 目的

本契约定义 MCP Server Tool 如何在不取得 Agent 控制权的前提下，被投影为现有 Java Tool Runtime 的只读工具。首个纵向切片只接受运维人员静态配置的本地 stdio Server、明确列入 Allowlist 的 `READ_ONLY` Tool 和文本结果。

MCP 是边缘协议适配器，不是新的 Agent Loop、审批系统、配置中心或插件管理器。Server 提供的名称、说明、Schema、Annotation、结果、错误和通知全部是不可信输入。

## 2. 范围

包含：

- MCP `2025-11-25` 生命周期中的初始化、能力协商、Initialized、Tools List/Call、Cancellation 和关闭。
- 官方 MCP Java SDK `2.0.0` 的 Client、Schema 与 Jackson 3 Mapper。
- Adapter 自有的有界 stdio Transport。
- 静态、版本化、严格 JSON 配置。
- 分页发现、稳定命名、安全 Schema 投影和不可降级风险策略。
- 共享 Deadline、每 Server 并发上限、Wire 上限、取消、Stale、故障隔离和进程回收。
- 通过现有不可变 `ToolRegistry` 和 Tool Loop 完成一次 Chat 闭环。

不包含：

- Streamable HTTP、SSE、OAuth、网络 Server 或远程发现。
- Resources、Prompts、Sampling、Roots、Elicitation、Tasks、Apps 或 Server Logging。
- 模型可调用或 HTTP 可调用的 Server 增删改。
- Python MCP 运行时、配置或 Fixture。
- `WRITE`、`EXTERNAL_SIDE_EFFECT`、Shell、文件写入、网络写入和真实第三方服务。
- 当前 Turn 热更新 Tool Registry、后台无限重连或 Tool Call 自动重放。

## 3. 固定安全不变量

1. `agent.mcp.mode` 默认且生产模板固定为 `DISABLED`。
2. `DISABLED` 时不得读取 MCP 配置、创建 SDK Client、启动进程、访问网络或发布 MCP Tool。
3. 启用时先完整验证全部静态配置，再启动第一个子进程。
4. 只投影“本地 Allowlist 中存在、风险明确为 `READ_ONLY`、远端 Catalog 中存在且 Schema 安全”的 Tool。
5. Server Annotation 不能赋予权限、降低风险、绕过审批或改变幂等语义。
6. SDK、Reactor、JSON-RPC、Process 和 Transport 类型只能出现在 `adapter-mcp`。
7. MCP Result 只能作为不可信 Tool Content 回送现有模型循环，不能变成 System Prompt、Approval Decision、配置或可执行指令。
8. MCP 失败、超时或取消不得提交当前会话轮次；普通非 MCP 聊天与其他健康 Server 保持可用。
9. 不自动重放结果未知的 Tool Call。
10. 命令、参数、工作目录、环境值、stdout、stderr、Server 原始错误和 Schema 原文不得进入模型、公开错误、生命周期事件或安全日志。

## 4. 协议与依赖基线

- 规范固定为稳定版 MCP `2025-11-25`。
- SDK 坐标固定为 `io.modelcontextprotocol.sdk:mcp:2.0.0`，许可证为 MIT。
- SDK 依赖 Reactor、Jackson 2 Annotations、Jackson 3 Databind 与 JSON Schema Validator；这些依赖只允许存在于 Adapter 依赖树。
- Client 只声明实际支持的能力；R5.1 不声明 Roots、Sampling 或 Elicitation。
- Server 必须协商出 SDK 支持且本契约允许的协议版本，并声明 Tools Capability；否则隔离该 Server。

官方 SDK 2.0.0 的内置 `StdioClientTransport` 不满足本契约：它会继承默认环境、在反序列化前执行无界 `BufferedReader.readLine()`，且取消 Client `Mono` 不会发送 MCP Cancellation。生产实现必须使用 Adapter 自有 Transport，并继续复用官方 Client、Schema、Request ID 与 Mapper。

## 5. 运行模式与配置

`agent.mcp.mode` 只接受：

| 值 | 行为 |
| --- | --- |
| `DISABLED` | 零配置访问、零 Client、零进程、零 MCP Tool |
| `STATIC_READ_ONLY` | 读取并验证单个静态配置文件，启动受控 stdio Server，只发布允许的只读 Tool |

启用时配置文件由 `agent.mcp.config-file` 指定。配置文档必须：

- `schemaVersion` 精确等于 `1`。
- 拒绝未知字段、符号链接、非普通文件、相对路径和超限文件。
- Server ID 唯一并匹配 `[a-z][a-z0-9_-]{0,15}`。
- Transport 精确为 `STDIO`。
- Executable、Working Directory 为绝对路径，解析 Real Path 后仍满足文件类型要求。
- Arguments 是字符串数组，不经 Shell 解释。
- Environment 只保存变量名；值只在启动瞬间从父进程读取。
- Tool Policy 只接受 `enabled=true|false` 与 `risk=READ_ONLY`；其他风险值在配置阶段失败。

版本 1 固定默认边界：

| 配置 | 默认值 | 合法范围 |
| --- | --- | --- |
| `max-servers` | `4` | `1..16` |
| `max-tools-per-server` | `32` | `1..128` |
| `max-list-pages` | `8` | `1..32` |
| `connect-timeout` | `5s` | 正数且小于模型超时 |
| `request-timeout` | `4s` | 正数且小于外层 Tool Timeout |
| `shutdown-timeout` | `2s` | 正数且不超过 `10s` |
| `max-schema-bytes` | `65536` | `1..1048576` |
| `max-wire-bytes` | `1048576` | `1..4194304` |
| `max-concurrent-calls-per-server` | `1` | `1..8` |

## 6. 子进程边界

- 使用 `ProcessBuilder` 的参数列表直接启动，不使用 Shell、不查找相对 Executable。
- 启动前调用 `processBuilder.environment().clear()`，只复制配置 Allowlist 中存在的变量。
- 不隐式复制 `PATH`、`HOME`、`SHELL`、Provider Key、云凭证或其他父进程环境。
- stdin/stdout 只承载换行分隔 JSON-RPC；Server 不得向 stdout 输出日志或横幅。
- stderr 必须持续有界 Drain，但内容只计数，不记录正文。
- 每条入站消息在 UTF-8 解码与 JSON 反序列化前实施 `max-wire-bytes`；超限立即使 Transport 失败并回收进程。
- 每条出站消息序列化后、写入前实施相同 Wire 上限。
- 关闭顺序为：拒绝新调用、取消在途请求、关闭 stdin、等待有界时间、TERM、再次等待、KILL、回收 Reader/Writer/Drain 任务。
- 关闭必须幂等，测试结束后所有 Reference Server `ProcessHandle.isAlive()` 为 `false`。

## 7. Catalog 与稳定命名

发现必须显式逐页调用 `tools/list`，页数、Tool 总数和 Cursor 都有界。以下情况隔离对应 Tool；协议或分页状态不可信时隔离整个 Server：

- 名称为空、重复或超限。
- 说明为空或超限。
- Input Schema 超过字节、深度或属性数量上限。
- Schema 不属于安全子集。
- Catalog 中不存在本地 Allowlist Tool。

本地名称基础格式为 `mcp_<serverId>__<remoteName>`：

- ASCII 字母、数字、`_`、`-` 原样保留；其他字符逐 Code Point 替换为 `_`。
- 一旦发生替换、截断或最终碰撞，追加 `_` 与 `SHA-256(serverId + NUL + remoteName)` 的前 10 个小写十六进制字符。
- 后缀加入前从前缀尾部截断，最终长度不超过 64。
- 名称映射只依赖 Server ID 与 Remote Name，不依赖发现顺序。
- Remote Name 大小写敏感；跨 Server 同名由 Server ID Namespace 区分。

## 8. Schema 投影

R5.1 只接受现有 Tool Runtime 可以完整验证的 Object Schema 子集：

- 顶层 `type` 必须为 `object`；未声明参数时规范化为空 Object Schema。
- 允许关键字只包括 `type`、`properties`、`required`、`additionalProperties` 和属性级 `enum`。
- `additionalProperties` 必须显式或规范化为 `false`。
- 属性类型只允许现有 Validator 支持的 `string`、`integer`、`number`、`boolean`、`object`、`array`。
- 不忽略或删除 `$ref`、组合关键字、条件关键字、动态关键字、Pattern、Format、Default、开放 Additional Properties 等未知语义。
- 所有 Map/List 进入 Kernel 前必须 Defensive Copy；SDK Schema 类型不得越过 Adapter 边界。

Unsupported Schema 只使该 Tool 不可发布，不把原始 Schema 或拒绝原因交给模型。

## 9. 调用、结果与并发

- Arguments 先经过现有 Tool Runtime 的 Object、Schema 和 UTF-8 字节预算，再传给 Adapter。
- Adapter 不附加 Prompt、历史、Memory、Session ID、Workspace、Provider 配置或父进程环境。
- 每 Server 使用公平许可，默认同时只允许一个在途 Tool Call；等待时间计入外层 Deadline。
- R5.1 只接受 MCP Text Content。多个 Text Block 用单个 `\n` 按 Wire 顺序连接。
- Image、Audio、Embedded Resource、Resource Link 和无法安全投影的 Structured Content 返回固定错误。
- `isError=true`、协议错误、Transport 错误、空结果和不支持内容都映射为稳定 `ToolResult.error`，不回显 Server 正文。
- 文本结果继续受现有 Tool Runtime 字符上限；超限不截断为成功。

固定公开结果：

| 场景 | Tool Result |
| --- | --- |
| Server 不可用、Stale 或协议错误 | `ERROR: MCP 工具不可用。` |
| Server `isError=true` 或调用异常 | `ERROR: MCP 工具执行失败。` |
| 内容类型不支持 | `ERROR: MCP 工具结果类型不支持。` |
| Wire 超限 | `ERROR: MCP 工具通信超过大小限制。` |
| 外层超时 | 继续使用现有 `TIMEOUT: 工具执行超时。` |
| Turn 取消 | 继续使用现有 `CANCELLED` 语义 |

## 10. Cancellation

MCP Cancellation 使用 `notifications/cancelled`，参数包含原始 JSON-RPC Request ID 和稳定、无敏感数据的 Reason。

- Adapter Transport 在发送 `tools/call` Request 时捕获 SDK 生成的 Request ID，并绑定到当前调用句柄。
- 外层 Tool Virtual Thread 被中断或 Turn Cancellation 触发时，调用句柄只允许一次发送 Cancellation。
- 发送取消后立即把该 Request ID 标记为已取消；迟到 Response 只用于释放 SDK Pending 状态，不得进入 Tool Result 或模型。
- 取消与正常 Response 竞态必须只有一个终态。
- Request 尚未写入 Wire 时取消，不发送悬空通知，但仍终止本地等待。
- 如果无法取得准确 Request ID、无法发送通知或无法证明迟到响应被丢弃，MCP Tool 必须保持禁用。

官方 SDK 当前没有自动完成以上语义，Adapter 不得把 `Mono.dispose()` 或线程中断错误地宣称为 Wire Cancellation。

## 11. 列表变化、断线与重连

- 收到 `notifications/tools/list_changed` 后 Server 状态立即变为 `STALE`。
- 已发布 Wrapper 从下一次状态检查起 Fail Closed；当前 Turn 不刷新或替换不可变 Registry。
- 断线使当前调用失败，不重放。
- 后续新的只读调用最多触发一次有界重连。
- 重连后重新发现 Catalog；规范化 Catalog 指纹必须与启动快照完全一致，才能恢复调用。
- 指纹不同、重复失败或关闭已开始时保持不可用，不启动后台重连、不复活进程。

## 12. 启动、提交与失败隔离

- 配置结构错误是 Operator Error：应用启动失败且零子进程。
- 单个合法 Server 的连接、协商或发现失败只隔离该 Server；普通聊天和其他 Server 继续启动。
- Tool Registry 在所有 Server 完成首次发现后一次性形成不可变快照。
- MCP 中间 Tool Call/Result 不写 SQLite；只有既有 ChatService 得到最终 Assistant 文本且 Turn 成功时，才提交真实 User/Assistant。
- MCP 不获得 Conversation Repository、Approval Port、Ledger Port、Memory Write Port 或配置写入 Port。

## 13. 验收门禁

必须使用仓库编译出的 Java Reference Server 验证：

- Initialize、能力与版本协商、Initialized、分页 List 和 Text Call。
- 并发 Response ID 关联、通知穿插、UTF-8、stderr Drain。
- Wire 前置上限、Schema/Result 上限、协议错误和突然退出。
- Wire 级 `notifications/cancelled`、准确 Request ID、迟到 Response 丢弃。
- Stale、一次重连、Catalog 指纹不变和变化两种路径。
- 正常关闭、TERM、KILL、幂等关闭和零孤儿进程。
- 现有 Tool Loop 的两次模型调用、最终提交与失败不提交。

最终门禁包括 Spotless、默认、Failure、Compat、Kernel 与 Adapter 依赖树、Secret、Workspace、进程和静态类型审计。测试不得运行 Python、Shell、网络、真实 MCP Server、真实 Secret 或真实用户数据。

## 14. 回退

回退只需把 `agent.mcp.mode` 设置为 `DISABLED` 并重启。版本 1 不修改 SQLite Schema、不持久化 MCP Catalog、不写入外部系统，因此不需要数据迁移或补偿操作。发现任何 Cancellation、Wire 上限或孤儿进程缺陷时，所有部署必须保持 `DISABLED`。
