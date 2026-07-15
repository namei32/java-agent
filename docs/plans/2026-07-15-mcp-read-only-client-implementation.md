# MCP 只读客户端纵向切片工作计划

- 状态：草案，待用户批准
- 日期：2026-07-15
- 阶段：R5.1
- 基线：R4.2 Task J1–J12 已完成，基线提交 `3f65f72`
- 实施授权：无；本文件只用于评审和拆分工作，不授权引入依赖、启动 MCP Server 或执行外部工具
- 关联 Roadmap：[Java 重写 Roadmap](../roadmap/java-rewrite-roadmap.md)
- 前置契约：[核心消息、生命周期与 Tool 契约](../contracts/core-message-lifecycle-tool.md)、[Tool Runtime 安全契约](../contracts/tool-runtime-safety.md)、[Tool 审批、副作用、幂等与沙箱安全契约](../contracts/tool-approval-side-effect-safety.md)

> 建议结论：R5.1 只实现“静态配置、默认关闭、stdio、明确 Allowlist、只读 Tool”的 MCP Client。MCP 只作为现有 Tool Runtime 的边缘适配器，不取得 Agent Loop、风险分类、审批、会话提交或配置写入权。

## 1. 目标

在不运行 Python、不连接真实 MCP Server、不开放副作用工具的前提下，形成首个可独立验收的 Java MCP 纵向切片：

```text
Operator-owned static config
  -> adapter-mcp
  -> MCP initialize / tools/list
  -> safe local Tool projection
  -> existing ToolRegistry / Tool Runtime
  -> MCP tools/call
  -> bounded ToolResult
  -> existing Chat Tool Loop
```

完成后，仓库应能用一个受控的 Java Reference Server 验证：连接、能力协商、分页发现、稳定命名、Schema 投影、只读调用、超时、取消、故障隔离和进程回收。生产和模板仍保持 MCP `DISABLED`。

## 2. 当前基线

Java 已具备：

- Application 自主管理的有界 Tool Loop。
- `ToolDefinition`、`ToolCall`、`ToolResult` 和 `ToolRisk`。
- Arguments/Result 上限、Schema 校验、共享超时、Virtual Thread、并发许可和 Turn Cancellation。
- Approval、整批门禁、幂等 Ledger Port 和 `UNKNOWN` 安全语义。
- 生产 `DenyAllApprovalPort`，无生产 Durable Ledger、无真实副作用 Tool。

仍存在的 MCP 接入差异：

- 当前没有 `adapter-mcp` 模块或 MCP SDK 依赖。
- `ToolRegistry` 在 ChatService 创建时形成不可变快照，不支持运行中任意增删工具。
- 本地 Tool 名最多 64 个字符且不接受 `.`；MCP Tool 名可更长并允许 `.`。
- 当前 Schema Validator 只支持安全 JSON Schema 子集，不能直接接收任意 MCP Schema。
- `Tool.execute` 没有显式 Cancellation 参数；必须证明 SDK 调用可以把线程取消转换为 MCP Cancellation，不能只停止本地等待。

## 3. 官方协议与 SDK 基线

截至本文日期，建议评审以下基线：

- MCP 稳定规范 `2025-11-25`：要求初始化、版本/能力协商、生命周期、请求超时和受控关闭。
- 标准 Transport 包含 stdio 与 Streamable HTTP；R5.1 只选择 stdio。
- Tool 支持分页 `tools/list`、`tools/call` 和可选 `notifications/tools/list_changed`。
- Server 提供的 Tool Annotation 不可信，不能直接决定本地风险或审批策略。
- 官方 Java SDK `2.0.0` 已声明对齐 `2025-11-25`，支持 JSON Schema 2020-12 校验与 Streamable HTTP 优先演进。

M0 必须重新验证正式发布版本、Maven 坐标、依赖树和许可证，再通过 ADR 固定版本。禁止把 Draft、Release Candidate 或未锁定的 Snapshot 放入生产依赖。

官方依据：

- [MCP 2025-11-25 Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)
- [MCP 2025-11-25 Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [MCP 2025-11-25 Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [MCP 2025-11-25 Cancellation](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation)
- [官方 MCP Java SDK 2.0.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0)

## 4. Python 参考行为与 Java 安全差异

Python `agent/mcp/` 只作为行为盘点参考，不作为 Java 运行依赖或 Fixture 生成器。

保留的可观察行为：

- stdio 子进程连接。
- `initialize`、`notifications/initialized`、`tools/list`、`tools/call`。
- Server 范围的 Tool Namespace。
- 单 Server 故障隔离与关闭时进程回收。

明确不迁移的行为：

- 不提供模型可调用的 `mcp_add`、`mcp_remove` 或任意命令执行入口。
- 不把 Secret 明文写入 `mcp_servers.json` 或其他 Workspace 文件。
- 不继承整个 Java 进程环境；子进程环境先清空，再复制显式 Allowlist。
- 不在错误、日志或模型结果中回显命令、参数、工作目录、stdout、stderr 或 Server 原始异常。
- 不手写会跳过错误 Response ID 的单通道 JSON-RPC 循环。
- 不把损坏配置静默解释为空配置。
- 不信任 Server 的 Tool Annotation、名称、说明、Schema 或错误正文。
- 不在后台无限重连，不自动重放结果未知的 Tool Call。

## 5. R5.1 范围

### 5.1 包含

- 新增独立 `adapter-mcp` Maven 模块及 ADR。
- 使用官方 Java SDK 的 MCP Client；SDK 类型只存在于 Adapter。
- 静态、只读、版本化 JSON 配置。
- stdio Transport、初始化、版本/能力协商和关闭。
- 分页 `tools/list`、稳定本地命名和严格 Schema 投影。
- 仅注册本地 Allowlist 中标为 `READ_ONLY` 的 Tool。
- 文本 Tool Result、稳定错误、Arguments/Result/Wire 上限。
- 共享 Deadline、MCP Cancellation、每 Server 并发上限。
- Server 崩溃、协议错误、超时和列表变化的 Fail Closed 行为。
- Java Reference Server、Contract、Failure 和 Chat 闭环验收。

### 5.2 不包含

- Streamable HTTP、HTTP+SSE、OAuth 或远程网络连接。
- MCP Resources、Prompts、Sampling、Roots、Elicitation、Tasks 或 Apps。
- 模型或 HTTP API 动态添加、删除、修改 Server。
- Python MCP 配置兼容或读取 Python `mcp_servers.json`。
- Tool Search、按需挂载、运行中动态更新 ToolRegistry。
- `WRITE`、`EXTERNAL_SIDE_EFFECT` 或风险未知 Tool。
- Shell、文件写入、Web 写入、日历写入或真实第三方服务。
- 自动后台重连、无限重试或 Tool Call 自动重放。
- 真实 MCP Server Smoke、真实 Secret、真实用户数据或生产启用。

## 6. 建议架构

### 6.1 依赖方向

```text
agent-bootstrap
  -> adapter-mcp
       -> agent-kernel
       -> official MCP Java SDK
  -> agent-application
       -> agent-kernel
```

- `agent-kernel` 继续只使用 JDK；不出现 MCP SDK、Reactor、Spring 或进程实现类型。
- `agent-application` 继续拥有 Tool Loop、风险不可降级、Approval、预算、取消和提交语义。
- `adapter-mcp` 把远端描述与结果转换为现有 Kernel `Tool`。
- `agent-bootstrap` 只负责配置、启动顺序、工具快照和关闭 Hook。
- 不使用 Spring AI MCP Starter 自动接管 Tool 注册或 ChatClient。

### 6.2 建议组件

| 组件 | 职责 |
| --- | --- |
| `McpRuntime` | 管理静态 Server Client、状态和幂等关闭 |
| `McpServerDefinition` | 已验证的静态 Server 配置，不包含 Secret 值 |
| `McpToolPolicy` | 由本地配置提供的启用与 `READ_ONLY` 风险决定 |
| `McpToolNameMapper` | 将 Server ID + Remote Name 映射为稳定、唯一、最多 64 字符的本地名 |
| `McpToolProjector` | 验证说明、Schema、Annotation 和输出能力，生成 Kernel `ToolDefinition` |
| `McpToolAdapter` | 调用 SDK、转换成功/错误/超时/取消并应用结果边界 |
| `McpRuntimeStatus` | 只提供无敏感数据的连接/退化计数 |

所有 SDK Request、Response、Transport、Session 和 Reactor 类型必须保持包私有或 Adapter 内部可见。

## 7. 静态配置草案

生产只从显式 `AGENT_MCP_CONFIG_FILE` 读取版本化配置；`DISABLED` 时不得解析文件或启动进程。

```json
{
  "schemaVersion": 1,
  "servers": [
    {
      "id": "docs",
      "transport": "STDIO",
      "executable": "/absolute/path/to/java",
      "arguments": ["-jar", "/absolute/path/to/docs-mcp.jar"],
      "workingDirectory": "/absolute/path/to/runtime",
      "environmentVariables": ["DOCS_MCP_TOKEN"],
      "tools": {
        "search": {"enabled": true, "risk": "READ_ONLY"}
      }
    }
  ]
}
```

约束：

- 配置文件必须是拒绝符号链接的有界普通文件；Executable 和 Working Directory 必须是绝对路径，并在解析 Real Path 后重新验证。拒绝未知字段和超限文件。
- 不通过 Shell 解释命令，不允许 `sh -c`、命令替换或单字符串命令行。
- `environmentVariables` 只保存环境变量名；值从父进程读取，禁止写入配置、日志、异常或测试快照。
- Process Environment 先清空，再复制明确列出的变量；不隐式继承 `PATH`、`HOME`、云凭证或 Provider Key。
- Server ID 建议固定为小写 `[a-z][a-z0-9_-]{0,15}`，配置内唯一。
- Remote Tool 不在 `tools` Allowlist、风险不是 `READ_ONLY`、Schema 不安全或能力不支持时不注册。
- 配置结构错误属于 Operator Error，启动失败且零子进程；Server 运行时不可用只隔离该 Server。

建议配置项与初始上限：

| 配置 | 默认值 | 约束 |
| --- | --- | --- |
| `agent.mcp.mode` | `DISABLED` | R5.1 只增加 `STATIC_READ_ONLY` |
| `agent.mcp.config-file` | 空 | 启用时必填 |
| `agent.mcp.max-servers` | `4` | `1..16` |
| `agent.mcp.max-tools-per-server` | `32` | `1..128` |
| `agent.mcp.max-list-pages` | `8` | `1..32` |
| `agent.mcp.connect-timeout` | `5s` | 正数且小于模型超时 |
| `agent.mcp.request-timeout` | `4s` | 正数且小于 Tool Runtime 超时 |
| `agent.mcp.shutdown-timeout` | `2s` | 正数且不超过 `10s` |
| `agent.mcp.max-schema-bytes` | `65536` | `1..1048576` |
| `agent.mcp.max-wire-bytes` | `1048576` | `1..4194304` |
| `agent.mcp.max-concurrent-calls-per-server` | `1` | `1..8` |

这些值必须在 M1 Fixture 和 Contract 中冻结后才能进入实现。

## 8. 行为契约草案

### 8.1 启动与发现

1. `DISABLED`：零配置文件访问、零 SDK Client、零子进程、零 MCP Tool。
2. `STATIC_READ_ONLY`：先完整验证全部静态配置，再启动任何 Server。
3. 每个 Server 完成版本/能力协商，只声明 R5.1 实际支持的 Client Capability。
4. Server 未声明 `tools`、协议版本不兼容或超过分页/数量上限时，只隔离该 Server。
5. `tools/list` 必须消费分页；重复 Remote Name、空名称、空说明、超限 Schema 或不安全 Schema 拒绝注册。
6. 只有本地 Allowlist 与远端 Catalog 同时存在的 Tool 才投影到 ToolRegistry。

### 8.2 稳定命名

本地名基础格式为 `mcp_<serverId>__<remoteName>`。由于本地 Tool 名最多 64 字符且 MCP 允许 `.` 和更长名称，映射必须：

- 保留合法的 ASCII 字母、数字、`_`、`-`。
- 非法字符转为 `_`。
- 一旦发生替换、截断或碰撞，追加由 `serverId + NUL + remoteName` 计算的短 SHA-256 后缀。
- 最终长度不超过 64，映射在重启和发现顺序变化后保持稳定。
- Remote Name 大小写敏感；不能依赖 Server 自报名称消除跨 Server 冲突。

M1 Fixture 必须固定普通名、包含点、Unicode、超长名、规范化碰撞和跨 Server 同名的精确结果。

### 8.3 Schema 投影

- MCP Schema 是不可信输入，先执行字节、深度、属性数和关键字检查。
- R5.1 只接受现有 Tool Runtime 能完整验证的严格 Object Schema 子集。
- 未声明参数的 Tool 投影为 `type=object`、空 Properties、`additionalProperties=false`。
- 禁止为了接入某个 Server 而把未知关键字静默删除或放宽 `additionalProperties`。
- Unsupported Schema 只使对应 Tool 不可用，不把原始 Schema 或原因暴露给模型。

### 8.4 调用与结果

- Arguments 先经过现有 Tool Schema 和 UTF-8 字节预算，再发送到 MCP。
- MCP Tool 只能收到当前调用的显式 Arguments；Adapter 不得附加完整 Prompt、历史、Memory、Session ID、Workspace 路径、Provider 配置或父进程环境。
- Adapter 使用 SDK 的多请求关联能力，不能依赖“逐行读取并跳过非预期 ID”。
- R5.1 只接受 Text Content；Image、Audio、Resource、Resource Link 和无安全投影的 Structured Content 返回稳定“不支持”错误。
- 多个 Text Block 按固定换行连接，进入现有 Result 字符上限；Wire 层另有字节上限。
- `isError=true`、协议错误和 Transport 错误映射为稳定 `ToolResult.error`，不回显 Server 原文。
- MCP Result 始终是不可信 Tool 内容，不能变成 System Prompt、Approval Decision 或可执行指令。

### 8.5 超时、取消与并发

- MCP 连接、分页和调用都有独立有界超时，并受现有 Tool Runtime 外层 Deadline 约束。
- 外层 Turn 取消或 Tool 超时后，必须停止本地等待并向仍在途的 MCP Request 发出 Cancellation。
- 取消后到达的迟到响应必须丢弃，不能进入模型或会话。
- 每 Server 公平并发许可默认 1；等待时间计入外层 Tool Deadline。
- M5 必须通过 Wire 级测试证明 Cancellation；如果官方 SDK 无法实现，暂停并先重新设计 Kernel Tool Cancellation Port。

### 8.6 列表变化、断线与重连

- 收到 `notifications/tools/list_changed` 后把该 Server 标为 `STALE`，现有 Wrapper Fail Closed。
- R5.1 不在运行中修改不可变 ToolRegistry，也不在当前 Turn 中替换 Schema。
- Transport 断线时当前调用失败，不自动重放。
- 后续新的只读调用可以触发一次有界重连；重建 Catalog 的规范化指纹必须与启动快照一致，否则继续保持 `STALE` 并等待重启。
- 无后台无限重连、指数风暴或关闭后的复活。

### 8.7 关闭

- Spring Shutdown 先阻止新调用，再取消在途请求。
- stdio Client 先关闭 stdin，等待有界时间；随后 TERM，仍未退出才 KILL。
- stderr Drain、SDK线程、Publisher、子进程和关闭 Hook 全部可回收且幂等。
- 测试必须通过 `ProcessHandle` 断言无孤儿 Reference Server。

## 9. TDD 实施顺序

### Task M0：Contract、Spec 与 ADR

状态：待批准后实施。

新增：

- `docs/contracts/mcp-client-tool-runtime.md`
- `docs/specs/2026-07-15-mcp-read-only-client-design.md`
- `docs/adr/0006-use-official-mcp-java-sdk.md`

工作：

- 重新验证稳定 MCP 规范和官方 Java SDK 版本。
- 固定 stdio-only、Tools-only、静态配置、风险和失败边界。
- 通过最小依赖 Spike 验证 SDK 版本协商、Jackson 3、Virtual Thread 阻塞和 Cancellation。
- Spike 只进入测试或临时分支；ADR 未批准前不进入生产 POM。

验证：`git diff --check`、Spotless 和现有完整默认门禁。

建议提交：`docs: 冻结 MCP 只读客户端契约`。

### Task M1：Java Contract Fixture

新增：

- `testdata/golden/mcp/java-mcp-client.json`
- Manifest `java-contract` Evidence。
- `McpGoldenManifestTest`。

Fixture 固定：配置、能力、分页、名称映射、Schema 投影、文本结果、错误、取消、列表变化和关闭 Case。Fixture 不由 Python 生成。

本任务属于契约资产，不人为破坏代码制造 RED。

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Pcompat -pl agent-kernel -am \
  -Dtest=McpGoldenManifestTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

建议提交：`test: 固定 Java MCP 客户端契约`。

### Task M2：`adapter-mcp` 模块与 SDK 隔离

先增加架构测试，RED 覆盖：

- 新模块和 Adapter 公共入口缺失。
- Kernel/Application 出现 MCP SDK、Reactor 或 Spring MCP 类型时失败。
- Bootstrap 不能绕过 Adapter 直接注入 SDK Client。

实现 Maven 模块、依赖方向、SDK BOM/版本和最小内部 Gateway。

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-mcp -am \
  -Dtest=McpArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

建议提交：`feat: 建立 MCP 客户端适配边界`。

### Task M3：静态配置与进程环境门禁

新增 `McpConfigurationTest`，RED 覆盖：

- 默认 `DISABLED` 零文件、零 SDK、零进程。
- 严格 JSON、版本、未知字段、文件/Server/Tool 数量和路径边界。
- 绝对 Executable、Working Directory、无 Shell。
- 环境变量只复制名称 Allowlist，配置和诊断不含值。
- 结构错误在首个子进程前失败；运行时 Server 缺失可隔离。

建议提交：`feat: 固定 MCP 静态配置门禁`。

### Task M4：Catalog、命名与 Schema 投影

新增 `McpToolNameMapperTest`、`McpToolProjectorTest`，RED 覆盖：

- M1 的精确名称映射和碰撞。
- 本地 Allowlist 与风险不可降级。
- 分页上限、重复名、描述/Schema 字节与深度。
- 安全 JSON Schema 子集、空参数和 Unsupported Tool 隔离。
- Server Annotation 永远不能降低风险。

建议提交：`feat: 投影安全 MCP 工具目录`。

### Task M5：stdio 生命周期、调用与 Cancellation

使用仓库内 Java Reference Server 增加 `McpStdioClientIT`、`McpCancellationIT`，RED 覆盖：

- Initialize、版本/能力协商、Initialized 和分页 List。
- 并发 Response ID 关联、通知穿插、UTF-8 和 stderr Drain。
- Text Call 成功、`isError`、协议错误和 Wire/Result 超限。
- 连接/请求超时、外层取消、迟到响应丢弃。
- 正常关闭、TERM、KILL 和零孤儿进程。

Reference Server 只能是仓库编译出的 Java 测试程序；禁止 Python、Shell、网络和真实数据。

建议提交：`feat: 实现有界 MCP stdio 客户端`。

### Task M6：现有 Tool Runtime 与 Chat 闭环

新增 `McpToolAdapterTest`、`McpToolLoopIT`，RED 覆盖：

- MCP Tool 通过现有 `ToolRegistry`、预算、Schema、许可和 Tool Loop 执行。
- `READ_ONLY` 之外的 MCP Tool 零注册、零模型发布、零执行。
- Tool Call、Java Adapter、MCP Result、第二次模型请求和最终 Conversation 提交。
- MCP 中间消息不写 SQLite；最终仍只提交真实 User/Assistant。
- MCP Result 不能改变 Approval、Risk、Ledger 或 Memory。

建议提交：`feat: 接入 MCP 只读工具闭环`。

### Task M7：故障隔离、Stale 与有界重连

新增 `McpFailureTest`，进入 `failure` Profile：

- 一个 Server 连接失败不影响普通聊天和其他 Server。
- stdout 非协议数据、损坏 JSON、错误 ID、突然退出、stderr Flood。
- `tools/list_changed` 后 Wrapper Fail Closed，当前 Turn 不热替换。
- 断线调用不重放；后续新调用最多一次有界重连。
- 关闭与重连竞态不复活进程、不泄漏许可。
- 所有错误不含命令、参数、环境、路径、stdout/stderr 或 Server Message。

建议提交：`test: 验证 MCP 故障与资源安全`。

### Task M8：Bootstrap、文档与最终门禁

工作：

- 配置绑定、默认关闭装配、Runbook 和能力矩阵。
- 生产 Bean 审计：无动态管理 Tool、无 HTTP Transport、无副作用 MCP Tool。
- Java Contract Fixture 由生产 Mapper/Projector/Adapter 消费。
- 记录准确测试数、依赖树和进程清理证据。

建议提交：`docs: 完成 MCP 只读客户端验收`。

## 10. 阶段门禁

每个行为任务执行有效 RED/GREEN，并记录失败原因与准确测试数。最终执行：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
./mvnw --batch-mode --no-transfer-progress -Pcompat verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
./mvnw --batch-mode --no-transfer-progress -pl adapter-mcp dependency:tree
```

静态审计：

- Kernel/Application 无 MCP SDK、Reactor、Spring MCP 或 JSON-RPC 类型。
- 默认配置零 MCP 文件、网络和子进程。
- 无 Python 命令、Python Fixture、Shell 或动态 `mcp_add/remove`。
- 无被跟踪 Secret、真实 MCP 配置、数据库、日志、stdout/stderr 快照或用户数据。
- 生产无 Streamable HTTP、Sampling、Elicitation、Tasks、Resources、Prompts 或副作用 Tool。
- 每个 Integration/Failure 测试结束后 Reference Server `ProcessHandle.isAlive()` 为 false。
- `git diff --check`、工作树、文档状态和提交历史一致。

真实 MCP Smoke 不属于默认门禁。它需要另行批准 Server、Executable、版本、Tool Allowlist、风险、Secret 来源、网络/费用、沙箱和数据范围。

## 11. 暂停条件

出现任一条件时停止实施并重新评审：

- 官方 SDK 无法把外层取消可靠转换成 MCP Cancellation。
- SDK 迫使 Reactor、Spring AI MCP 或 Transport 类型进入 Kernel/Application。
- 目标 Server 只能通过 Shell、`npx` 动态下载、相对 Executable 或全环境继承启动。
- 目标 Tool Schema 需要通过删除未知关键字或开放 `additionalProperties` 才能注册。
- 需要 Streamable HTTP、OAuth、Sampling、Elicitation、Resources、Prompts 或 Tasks。
- 需要 `WRITE`、`EXTERNAL_SIDE_EFFECT`、真实 Approval Channel 或生产 Durable Ledger。
- 需要把 Secret 值写入配置、日志、异常、Fixture 或 Workspace。
- SDK Transport 无法在反序列化前实施 Wire 字节上限。
- 无法证明超时、关闭和测试结束后不存在孤儿进程。

## 12. 完成定义

- M0–M8 全部完成并具有有效 TDD/Contract 证据。
- `DISABLED` 为模板和生产默认，零文件、零进程、零网络。
- 静态启用时只发布本地 Allowlist 的 `READ_ONLY` MCP Tool。
- MCP Server 不能控制风险、审批、循环、会话提交或配置。
- 单 Server 故障不使普通聊天不可用。
- Timeout/Cancellation 传播到 MCP，迟到响应不进入模型。
- Tool Catalog、Schema 和 Result 全部有界且 Fail Closed。
- 子进程、流、线程和 SDK Client 能可靠回收。
- 默认、`failure`、`compat`、依赖、Secret 和进程审计全部通过。
- 未运行 Python、真实 MCP Server、真实 Secret 或真实副作用。

## 13. 后续但不属于 R5.1

R5.1 完成后再分别审批：

1. R5.2：认证后的 Streamable HTTP Client、DNS/Origin/Redirect/SSRF 边界。
2. R5.3：真实 Approval Channel、Durable Ledger 就绪后的副作用 MCP Tool。
3. R5.4：Tool Search、按需挂载和安全 Catalog 刷新。
4. R6：Message Bus、CLI/渠道和流式生命周期。
5. R7/R8：Plugin、Skill、Scheduler、Proactive、Drift 与 Subagent。

Memory 自动提取与 Optimizer 继续冻结，不与 MCP 首个纵向切片并行实施。
