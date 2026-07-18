# R7 插件与扩展运行时契约

- 阶段：R7
- 状态：已由“完成 R7–R9”授权冻结；生产默认 `DISABLED`
- 日期：2026-07-18
- Python 基准：`agent/plugins/`
- 关联 ADR：[ADR-0012：使用 ServiceLoader 与隔离 stdio Plugin Bridge](../adr/0012-use-service-loader-and-isolated-stdio-plugin-bridge.md)
- 关联设计：[R7 插件扩展运行时设计](../specs/2026-07-18-plugin-extension-runtime-design.md)
- 实施计划：[R7 插件扩展运行时计划](../plans/2026-07-18-r7-plugin-extension-runtime-implementation.md)

## 1. 目标与非目标

R7 只提供可审计、可停止、有界的 Java 扩展点。它兼容 Python 插件的发现、元数据、配置、生命周期和
观察型 Hook 概念，但不兼容运行时猴子补丁、共享 Python 全局变量、任意文件加载或未经审批的 Tool/Channel
注入。

V1 不授权真实 Python 插件、网络、Secret、Workspace 写入、Side Effect Tool、Channel 注册或主动任务。
这些能力必须分别经过已有 Tool/Channel/R8 Contract。

## 2. 模式、来源与边界

`agent.plugins.mode` 仅接受严格大写值：

| 模式 | 行为 |
| --- | --- |
| `DISABLED` | 默认；不扫描目录、不读取 Manifest、不创建进程、线程、随机值或状态文件 |
| `JAVA_SERVICE` | 只加载应用 classpath 上由 `ServiceLoader` 发现的受信 Java Plugin |
| `EXTERNAL_STDIO` | 在显式 allowlist 的本地启动描述下创建隔离 stdio Bridge；不能从 Python 目录 import |

`JAVA_SERVICE` 与 `EXTERNAL_STDIO` 都要求全局 Tool Runtime 至少为 `READ_ONLY`；否则启动失败。插件不继承
Agent 的 Workspace、Session、Memory、Approval、Token 或原始 Prompt 引用。

## 3. 版本化 Manifest 与身份

每个候选必须提供一个不含 Secret 的 Manifest：

```json
{
  "schemaVersion": 1,
  "id": "calendar-observer",
  "version": "1.2.3",
  "apiVersion": 1,
  "kind": "JAVA_SERVICE",
  "capabilities": ["TURN_TAP"]
}
```

- `id` 是 `^[a-z][a-z0-9-]{0,62}$`，全局唯一；重复、空值、大小写变体或未知字段语义均拒绝。
- `version` 只用于观察与兼容诊断，不决定加载优先级。
- V1 `kind` 只能为 `JAVA_SERVICE` 或 `EXTERNAL_STDIO`，且必须与配置来源一致。
- `capabilities` 只能声明 `TURN_TAP`、`TOOL_TAP`、`PROACTIVE_TAP`；声明 Tool、Channel、文件、网络或
  写入能力稳定拒绝为 `PLUGIN_CAPABILITY_UNAVAILABLE`。

## 4. 生命周期与 Hook

Plugin 生命周期严格为：`DISCOVERED -> STARTING -> ACTIVE -> STOPPING -> STOPPED`，失败进入
`FAILED`。每个状态转换产生脱敏审计事件；失败 Plugin 不阻止主 Agent、消息提交、Channel 或其他 Plugin。

Hook 只接收不可变、安全投影：请求/Turn 随机引用的域隔离 Hash、阶段、稳定结果码、耗时、计数和
`OutboundMessage` 安全投影。不得收到 Token、原始 Session/Turn、Prompt、正文、Memory、Tool
Arguments/Result、路径、异常正文或任意可变运行时对象。

V1 Hook 是 `TAP`：不能修改输入、阻塞主链路、取消 Turn 或改变 Tool/消息结果。每次调用有独立超时、
并发和事件字节上限；超时、协议错和 Runtime Failure 只禁用当前 Plugin 并记录稳定码。

## 5. External stdio Bridge

Bridge 使用 UTF-8、单行 JSON、请求/响应关联 `requestId` 的协议。`hello` 必须先返回 Manifest，随后
只接受 `turn.tap`、`tool.tap`、`proactive.tap` 与 `shutdown`。单条线帧、并发请求、待处理请求和关闭等待
均受配置硬上限约束；超限、非法 JSON、错误关联或子进程退出稳定隔离，不重连、不重放事件。

启动命令仅来自受限的静态配置：无 Shell、无环境变量 Secret 透传、无工作区相对路径、无自动发现目录。
Bridge 的 stdout 不能写日志，stderr 只记录长度与稳定类别。

## 6. 配置与审计

配置只接受显式 Plugin ID、来源、版本范围、启动描述和有界预算；配置值不会出现在 `toString()`、
诊断、HTTP、SSE 或普通日志。默认 Safe Audit 字段为时间、Plugin ID Hash、动作、结果、稳定码、计数、
耗时和来源类别。

V1 稳定码：`PLUGIN_DISABLED`、`PLUGIN_MANIFEST_INVALID`、`PLUGIN_DUPLICATE_ID`、
`PLUGIN_API_INCOMPATIBLE`、`PLUGIN_CAPABILITY_UNAVAILABLE`、`PLUGIN_TIMEOUT`、
`PLUGIN_PROTOCOL_INVALID`、`PLUGIN_PROCESS_EXITED`、`PLUGIN_SHUTTING_DOWN`。

## 7. 兼容、验收与暂停

Java-owned Fixture 固定 Manifest、生命周期、Hook 顺序、超时、重复 ID、Protocol 与 Disabled 场景。V2 才能
增加可变 Gate、Tool/Channel 贡献、热更新、网络 Bridge 或 Python 进程内加载。

验收要求：Disabled 零资源；确定发现/排序；一个 Plugin 失败隔离；超时/关闭无泄漏；Bridge 不重放；
Kernel 无框架/进程依赖；默认、`failure`、`compat` 阶段门禁通过。
