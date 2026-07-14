# Tool Runtime 安全加固设计

- 状态：已实现并验证
- 日期：2026-07-14
- 验证日期：2026-07-14
- Contract：[Tool Runtime 安全契约](../contracts/tool-runtime-safety.md)
- Roadmap：R3.1 Tool Runtime 安全加固

## 1. 目标

在现有只读最小 Tool Loop 上实现已批准的运行模式、资源预算、Schema 校验、超时、并发许可、取消和 Provider Arguments 边界，同时保持 HTTP 与 SQLite Schema 不变。

## 2. 非目标

- 不引入副作用工具、审批、自动重试、并行 Tool Call、MCP 或持久化 Tool Transcript。
- 不承诺中断已发出的同步模型 HTTP 请求。
- 不修改 Python TOML 共同活动字段；新增设置仍使用 Java 运维环境变量。
- 不在自动测试中访问真实 Provider。

## 3. Application 设计

新增 `ToolRuntimeSettings`，包含：

- `ToolRuntimeMode`：`DISABLED` 或 `READ_ONLY`。
- 单响应、单轮调用上限。
- 单工具 Deadline、全局执行许可数和 Result 字符上限。

`ChatService` 保留现有普通 `chat(command)` 入口，并增加带项目自有 `TurnCancellation` 的重载。普通入口使用永不取消的 Token，现有 HTTP Contract 不变。

`ToolLoop` 每轮执行：

1. 调用模型前后检查取消。
2. `DISABLED` 模式只发送空 Tool Definition；模型仍返回 Tool Call 时按非法响应失败。
3. 对整个批次先检查单响应与累计预算。
4. 对所有已注册调用先计算 Schema 校验结果，再开始顺序执行。
5. 超时、参数错误和 Result 超限作为安全 Tool Result 回送模型。
6. 取消和调用预算超限立即终止，不提交轮次。

## 4. Schema Validator

Application 实现纯 JDK Validator。工具注册时递归编译并校验 Schema，只允许 Contract 版本 1 的关键字和类型；未知关键字、错误原生类型或无效 Required 引用会使启动失败。

运行时先验证 `required`、未知字段、类型和 `enum`，再调用具体工具。校验失败只返回固定正文 `工具参数无效。`。

## 5. 执行、超时与并发

`ToolRegistry` 使用一个公平 `Semaphore` 作为 JVM 内共享执行许可。每次调用：

- 获取许可和执行共用一个基于 `System.nanoTime()` 的 Deadline。
- 获得许可后使用 `Thread.ofVirtual()` 启动一个 `FutureTask`。
- 许可由 Virtual Thread 的外层 Worker 在 `finally` 中释放；即使 Future 在任务正文启动前已取消也不会泄漏。
- Virtual Thread 启动失败时同步释放许可并返回固定安全错误。
- 超时取消 Future 并中断执行线程，返回 `TIMEOUT`。
- 取消 Token 注册当前等待线程或执行线程的中断回调。
- 不使用 Preview API、`Thread.stop`、自动重试或工具并行。

测试使用 CountDownLatch/可控 Token 建立顺序，不使用任意 `sleep` 判断并发正确性。

## 6. 取消协议

新增 `TurnCancellation` 只读接口和 `TurnCancellationSource`：

- Source 只能从调用方触发一次取消。
- Token 可以查询状态并注册可关闭的取消回调。
- 回调异常被隔离。
- 活动工具取消产生 `CANCELLED` 生命周期结果，然后 Turn 以 `TURN_CANCELLED` 失败。
- 模型返回后发现取消时直接终止，不执行工具或提交。

## 7. Adapter 与 Bootstrap

`SpringAiChatModelAdapter` 在 JSON 解析前按 UTF-8 计算 Arguments 字节数，超限转换为 `InvalidModelResponseException`。

`AgentProperties.Tools` 绑定 Contract 中全部新增配置并交叉校验：

- 正整数约束。
- 每轮上限不得小于单响应上限。
- Tool Timeout 必须小于 Model Timeout。

`ApplicationConfiguration` 在 `DISABLED` 模式传入空工具列表，在 `READ_ONLY` 模式注册 `current_time`。

## 8. 错误与生命周期

新增：

- `ToolCallLimitExceededException` -> `TURN_FAILED.status=TOOL_CALL_LIMIT_EXCEEDED` -> HTTP 502。
- `TurnCancelledException` -> `TURN_FAILED.status=TURN_CANCELLED`；当前 HTTP 没有取消触发入口。

沿用 `TIMEOUT`、`CANCELLED` Tool Result Status。所有新增错误继续使用固定公开正文，不记录参数、结果或异常详情。

## 9. Golden 与验证

扩展 Migration Contract Tool Golden，覆盖模式、预算、Schema、Result、超时、并发许可和取消。Compat 测试必须执行生产代码。

阶段门禁：Spotless、默认 Reactor、Failure、Compat、架构、Secret 和 Workspace 检查。真实 Provider Tool Smoke 仍是人工发布门禁；2026-07-14 经授权对 DeepSeek `deepseek-v4-flash` 执行的补充 Smoke 已覆盖 Tool Call、Java 执行、结果回送、最终文本和 SQLite 提交并通过，但不自动启用部署。

## 10. 回退

设置 `AGENT_TOOL_MODE=DISABLED` 即可停止发送 Tool Definition，不迁移数据库、不改变 HTTP。若安全 Runtime 自身存在问题，可回退到合并提交 `4c2cefd`，其数据仍兼容。
