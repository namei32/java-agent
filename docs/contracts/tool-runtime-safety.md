# Tool Runtime 安全契约

- 状态：已批准
- 契约版本：1
- 批准日期：2026-07-14
- 前置契约：[核心消息、生命周期与 Tool 契约](core-message-lifecycle-tool.md)
- 适用阶段：R3.1 Tool Runtime 安全加固
- 实施状态：已实现并通过格式、默认、Failure、Compat、架构、Secret 与 Workspace 门禁；DeepSeek `deepseek-v4-flash` 真实 Tool Smoke 已通过

## 1. 目的

本契约定义只读 Tool Runtime 的资源边界、参数校验、超时、取消、并发、失败和 Provider 兼容语义。目标是在继续增加工具前，使现有最小 Tool Loop 具备可预测、可限制、可回退和可验证的运行边界。

本契约不会授权任何副作用工具。文件写入、Shell、网络写入、消息发送、记忆修改和外部系统变更仍必须等待独立的 Approval Contract、幂等语义和沙箱设计。

## 2. 决策输入

2026-07-14 在隔离 Workspace 中执行了 DeepSeek Smoke：

- 环境变量配置检查通过，API Key 仅确认状态为 `PRESENT`。
- DeepSeek 基础文本 Chat Completions 直连返回 HTTP `200`。
- 应用启动、HTTP、SQLite 和健康检查正常。
- 应用携带 Tool Schema 的两次聊天请求均返回安全 HTTP `502`，没有进入成功 Tool Loop。
- Provider Function Calling 探测没有形成稳定成功证据。
- 隔离 SQLite 最终保持 `sessions=0`、`messages=0`，失败轮次没有落盘。
- 未输出或提交 API Key、Provider 原始错误正文、真实 Workspace、数据库或运行日志。

因此本次 Smoke 结论为：基础 Provider 连通正常，但当前 DeepSeek Function Calling 路径尚未通过。后续实现必须提供显式禁用工具的回退模式，并把真实 Provider Tool Smoke 作为启用 Tool Runtime 的发布门禁；不得把本次结果描述为 Tool Smoke 成功。

同日完成 Spring AI Provider Options 与许可生命周期修复后，再次对 DeepSeek `deepseek-v4-flash` 执行经授权的真实 Smoke：普通回答成功，模型返回 `current_time` Tool Call，Java 执行工具并回送 Result，模型返回最终文本，生命周期记录两次模型请求和成功工具事件，隔离 SQLite 只提交最终 User/Assistant 两条消息。该结果只证明此 Provider/模型组合的当前能力，不覆盖其他组合，也不自动授权把任何部署从 `DISABLED` 切换为 `READ_ONLY`。

## 3. 范围

包含：

- `DISABLED` 与 `READ_ONLY` 两种工具运行模式。
- 每批、每轮、Arguments、Result、执行时间和跨会话并发预算。
- JSON Schema 子集校验和具体工具的二次校验。
- 未知工具、非法参数、工具异常、超时和取消。
- 稳定生命周期事件、错误类别和会话提交语义。
- Provider Function Calling 能力验证和安全回退。

不包含：

- 副作用工具和人工审批。
- 自动重试、补偿事务和幂等键。
- 工具并行执行。
- MCP、Plugin、Tool Search、动态注册和远程工具。
- Tool Transcript 持久化。
- HTTP 客户端断开自动传播为取消；这需要单独的 Servlet/Channel Contract。

## 4. 固定安全不变量

1. 生产 Registry 只接受 `READ_ONLY` 工具。
2. Spring AI 和 Provider SDK 只传输 Schema 与消息，不得执行真实工具。
3. 同一批 Tool Call 始终按模型顺序串行执行。
4. 整批调用必须先完成结构和预算校验，再执行其中任何工具，避免半批执行。
5. Tool Runtime 不自动重试任何工具。
6. Tool Arguments、Result、异常消息和堆栈不得进入生命周期事件、HTTP 错误或安全日志。
7. 失败、取消或预算耗尽时不得提交当前会话轮次。
8. 工具模式或 Provider 能力不足时不得静默降级后伪装成 Tool 执行成功。

## 5. 运行模式

新增配置 `agent.tools.mode`，环境变量为 `AGENT_TOOL_MODE`：

| 值 | 行为 |
| --- | --- |
| `DISABLED` | 不注册生产工具，不向模型发送 Tool Definition，维持普通被动聊天 |
| `READ_ONLY` | 只注册经过审查的只读工具，启用本契约的全部预算和执行语义 |

默认值为 `READ_ONLY`，保持当前最小 Tool Loop 行为。本契约实施后，Provider 尚未通过 Function Calling Smoke 的部署必须显式设置为 `DISABLED`。

不支持的值启动失败。未来即使出现 `APPROVAL_REQUIRED` 等名称，版本 1 也不得接受。

当模式为 `DISABLED` 时，如果 Provider 仍返回 Tool Call，该响应属于 `INVALID_MODEL_RESPONSE`；不得执行，也不得在没有审计证据的情况下自动重新请求模型。

## 6. 资源预算

版本 1 固定以下安全默认值：

| 配置 | 环境变量 | 默认值 | 规则 |
| --- | --- | --- | --- |
| `agent.tool-loop.max-iterations` | `AGENT_TOOL_MAX_ITERATIONS` | `6` | 一轮允许的模型调用次数 |
| `agent.tools.max-calls-per-response` | `AGENT_TOOL_MAX_CALLS_PER_RESPONSE` | `8` | 单个模型响应允许的 Tool Call 数 |
| `agent.tools.max-calls-per-turn` | `AGENT_TOOL_MAX_CALLS_PER_TURN` | `16` | 一轮累计 Tool Call 数 |
| `agent.tools.timeout` | `AGENT_TOOL_TIMEOUT` | `5s` | 等待并执行单个工具的总时限 |
| `agent.tools.max-concurrent-calls` | `AGENT_TOOL_MAX_CONCURRENT_CALLS` | `32` | JVM 内跨会话同时占用的工具执行许可数 |
| `agent.tools.max-argument-bytes` | `AGENT_TOOL_MAX_ARGUMENT_BYTES` | `16384` | 单个 Arguments JSON 的 UTF-8 字节上限 |
| `agent.tools.max-result-characters` | `AGENT_TOOL_MAX_RESULT_CHARACTERS` | `20000` | 单个 Tool Result 的 Unicode 字符上限 |

约束：

- 所有整数必须大于零。
- `max-calls-per-turn` 必须大于或等于 `max-calls-per-response`。
- Tool Timeout 必须小于模型请求 Timeout。
- 配置非法时应用启动失败，不静默使用其他值。
- 预算只允许通过配置收紧或显式放宽；任何默认值变化都属于契约变更。

## 7. Tool Call 批次校验

模型响应进入执行前，按顺序完成：

1. 响应必须满足现有 Tool Call ID、名称、JSON Object 和批内 ID 唯一约束。
2. Adapter 在解析前检查原始 Arguments JSON 的 UTF-8 字节数。
3. 检查单响应调用数和本轮累计调用数。
4. 检查每个已注册工具的 Schema 和风险等级。
5. 全部通过后，才开始执行第一个调用。

Arguments 超出字节上限或不是 JSON Object，属于 `INVALID_MODEL_RESPONSE`。

批次超过单响应或单轮上限时抛出稳定的 `ToolCallLimitExceededException`：

- 整批不执行。
- 不追加 Tool Result。
- 不再请求模型。
- 本轮不提交。
- `TURN_FAILED.status` 为 `TOOL_CALL_LIMIT_EXCEEDED`。
- HTTP 映射为 `502`，不返回具体数量、Call ID 或 Arguments。

## 8. 参数 Schema 校验

版本 1 在 Application 层实现纯 JDK JSON Schema 子集，不向 Kernel 引入 Jackson 或 Schema 框架。支持：

- 顶层 `type: object`。
- `properties`。
- `required`。
- `additionalProperties: false`。
- 属性类型 `string`、`integer`、`number`、`boolean`、`object`、`array`。
- `enum`。

不支持的 Schema 关键字必须在工具注册时启动失败，不得在运行时忽略后形成虚假校验。

Schema 校验通过后，具体工具仍必须执行领域二次校验。参数校验失败产生安全 `ERROR` Tool Result，正文固定为 `工具参数无效。`，循环继续；不得包含字段值、Schema 全文或异常信息。

## 9. 执行、并发与超时

- 单个 Turn 内继续串行执行，不引入并行。
- 不同 Session 可以并行，但所有工具共享 JVM 级执行许可，默认最多 `32` 个。
- 获取执行许可和实际执行共享同一个 `agent.tools.timeout` Deadline。
- 使用 JDK 21 Virtual Thread 执行工具；禁止使用 Preview API。
- Deadline 到期后取消 Future 并发出线程中断。
- 执行许可由实际工具任务持有，只能在任务真正退出的 `finally` 中释放；Future 被标记取消但任务尚未退出时不得提前归还许可。
- Worker 已启动但 Future 正文因预先取消而未执行时，外层 Worker 仍必须释放许可；线程启动失败也必须同步释放许可。
- 所有可注册工具必须在评审中证明会响应中断并可靠释放资源。
- 工具忽略中断视为实现缺陷；Runtime 不得通过 `Thread.stop` 等不安全机制强杀线程。

超时产生 `TIMEOUT` Tool Result，正文固定为 `工具执行超时。`，同批剩余调用继续，随后把全部结果交回模型。工具超时不自动重试。

等待执行许可本身耗尽 Deadline 时同样产生 `TIMEOUT`，避免无限排队。

## 10. Result 边界

- `null` Result 转换为 `ERROR`，正文固定为 `工具执行失败。`。
- 工具抛出的运行时异常转换为 `ERROR`，正文固定为 `工具执行失败。`。
- 未知工具转换为 `ERROR`，正文固定为 `工具不可用。`。
- Result 超过字符上限时不得截断后伪装成功；转换为 `ERROR`，正文固定为 `工具结果超过大小限制。`。
- 安全 Result 可以回送模型，但不得直接作为 HTTP 响应、日志或持久化消息。

## 11. 取消语义

Application 增加项目自有的 Turn Cancellation Token，不依赖 Spring 或 Reactor 类型。

- 模型调用前后、工具开始前和提交前都必须检查取消状态。
- 活动工具被取消时中断其 Virtual Thread。
- 活动 Tool Call 发出 `TOOL_CALL_COMPLETED.status=CANCELLED`。
- 尚未开始的同批调用不执行，也不伪造 Started/Completed 事件。
- 取消后不再调用模型，不提交会话，抛出稳定的 `TurnCancelledException`。
- `TURN_FAILED.status` 为 `TURN_CANCELLED`。

版本 1 只建立 Application 协议和测试入口；在渠道或 HTTP 取消 Contract 批准前，不声称浏览器断开会自动取消执行。

现有同步 `ChatModelPort` 不提供进行中的 Provider 请求取消。版本 1 在模型调用返回后观察取消并禁止继续执行或提交，但不声称能够立即中断已发出的模型 HTTP 请求；若要提供该能力，必须先扩展 Model Port Contract。

## 12. 生命周期与稳定状态

沿用既有事件顺序，并增加以下稳定失败状态：

| 场景 | Tool 完成状态 | Turn 失败状态 | 是否继续模型 |
| --- | --- | --- | --- |
| 未知工具 | `ERROR` | 无 | 是 |
| 参数校验失败 | `ERROR` | 无 | 是 |
| 工具异常或空结果 | `ERROR` | 无 | 是 |
| Result 超限 | `ERROR` | 无 | 是 |
| 工具超时 | `TIMEOUT` | 无 | 是 |
| Turn 取消 | `CANCELLED`（存在活动调用时） | `TURN_CANCELLED` | 否 |
| Tool Call 预算超限 | 无 | `TOOL_CALL_LIMIT_EXCEEDED` | 否 |
| 模型迭代超限 | 最后调用的实际状态 | `TOOL_LOOP_LIMIT_EXCEEDED` | 否 |

`DENIED` 和 `SKIPPED` 在版本 1 中继续保留但不主动产生。它们只能在未来 Approval/调度契约批准后启用。

所有事件继续禁止携带消息正文、Arguments、Result、异常消息、堆栈、API Key 和 Session 原文。

## 13. Provider 兼容和发布门禁

每个计划启用 `READ_ONLY` 的 Provider/模型组合必须完成真实 Tool Smoke：

1. 无工具需求的普通回答成功。
2. 模型成功返回一个 `current_time` Tool Call。
3. Java 执行工具并把 Tool Result 回送模型。
4. 模型返回最终文本。
5. SQLite 只提交最终 User/Assistant。
6. Provider 拒绝、超时和非法响应仍映射为稳定公开错误。

Smoke 必须记录 Provider、模型能力类别、日期、结果和失败阶段，但不得记录密钥、完整 Prompt、Arguments、Result 或 Provider 原始错误正文。

如果 Smoke 未通过：

- 部署必须使用 `AGENT_TOOL_MODE=DISABLED`，或停止发布。
- 不得捕获 Provider Tool 错误后自动移除 Schema 并重试普通聊天，因为这会改变调用次数、费用和可观察语义。
- 修复 Provider Adapter 必须走独立 Spec、聚焦 RED/GREEN 和兼容测试。

## 14. 持久化与回退

- Tool Transcript 继续只存在于当前 Turn 内存，不写入 SQLite。
- 只有得到最终 Assistant 文本且提交前未取消时，才原子追加 User/Assistant。
- 切换到 `DISABLED` 不需要迁移数据库或修改 HTTP Contract。
- 本契约实施不得修改现有 SQLite Schema。
- 副作用工具出现前，失败回退仍以“停止当前 Turn、不提交”为边界。

## 15. Golden 与测试要求

必须扩展 Tool Golden，至少增加：

- 单响应调用数超限，整批零执行。
- 单轮累计调用数超限。
- Arguments 字节超限。
- Required、类型、Enum 和未知字段校验失败。
- Result 超限。
- 工具超时后模型恢复。
- 等待并发许可超时。
- 取消活动工具并且不提交。
- `DISABLED` 模式不发送 Tool Definition。

Java 测试必须执行生产 Runtime，并断言执行顺序、模型调用次数、生命周期、安全结果和提交决策。时间、线程调度和 Deadline 使用可控 Fake/Clock，不依赖不稳定 `sleep`。

阶段门禁继续为：

```bash
./mvnw spotless:check
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

真实 Provider Smoke 是人工发布门禁，不属于默认 CI。

## 16. 变更审批

以下变化必须修改本契约并重新批准：

- 开放副作用工具或审批执行。
- 引入工具并行、自动重试或补偿。
- 修改任何安全默认预算。
- 改变超时、取消、批次原子校验或提交语义。
- 新增 Tool Result/Lifecycle 稳定状态。
- 允许 Provider SDK 执行工具或允许失败后静默移除 Schema 重试。
- 持久化 Tool Transcript 或向 HTTP 暴露 Arguments/Result。
