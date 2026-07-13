# 最小 Tool Loop 设计

- 状态：已批准
- 日期：2026-07-13
- Contract：[核心消息、生命周期与 Tool 契约](../contracts/core-message-lifecycle-tool.md)
- Roadmap：R3 Tool Loop

## 1. 目标

在现有同步被动聊天纵向切片中加入项目自有的最小 Tool Loop：模型可以请求一个或多个只读工具，Java 按顺序执行并把结果回送模型，最终仍只提交并返回一条 Assistant 文本。

## 2. 非目标

- 不实现写文件、Shell、Web、消息发送或其他副作用工具。
- 不实现审批、重试、Tool Timeout、取消、并行执行、MCP、Plugin Hook 或 Tool Search。
- 不修改公开 HTTP Request/Response。
- 不修改 SQLite Schema，不持久化 Tool Transcript。
- 不实现流式输出、多模态工具结果或 Thinking。
- 不激活 Python TOML 的 `agent.max_iterations`；第一阶段使用 Java 运维配置。

## 3. 模块设计

`agent-kernel` 增加：

- 工具调用、定义、风险、结果状态和结果值对象。
- 可表达 Assistant Tool Call 与 Tool Result 的模型消息。
- 模型请求携带工具定义，模型响应携带零个或多个 Tool Call。
- 生命周期事件与观察 Port。
- Tool Port，不依赖 Spring、Jackson 或供应商 SDK。

`agent-application` 增加：

- 只读 Tool Registry，启动时拒绝重复名称和非只读工具。
- `ToolLoop`，负责模型迭代、顺序执行、错误结果和生命周期轨迹。
- `ChatService` 继续负责 Session Gate、历史、最终原子提交；把一次模型调用替换为 Tool Loop。

`adapter-spring-ai` 增加：

- Function Schema 映射。
- Assistant Tool Call 和 Tool Result 消息映射。
- Tool Call Arguments JSON Object 解析与安全错误转换。
- 只把 Callback 当作 Schema Carrier，禁止 Adapter 或 Spring AI 执行项目工具。

`agent-bootstrap` 增加：

- `agent.tool-loop.max-iterations`，默认 `6`，环境变量为 `AGENT_TOOL_MAX_ITERATIONS`。
- 一个无副作用的 `current_time` 工具，返回 `Clock` 的 ISO-8601 UTC 时间。
- 生命周期默认使用安全 No-op；现有模型、数据库和请求日志保持不记录正文。

## 4. 主要类型

```text
ChatService
  -> ToolLoop
       -> ChatModelPort
       -> ToolRegistry -> Tool
       -> TurnLifecycleObserver
  -> SessionRepository.appendTurn
```

模型消息使用一个项目自有不可变类型，保留现有 `ChatMessage(role, content)` 便利构造，并增加 Tool Call/Tool Result 工厂方法。持久化层继续只接受普通 User/Assistant `ChatMessage`。

## 5. 失败语义

- 模型响应没有文本也没有 Tool Call：`InvalidModelResponseException`。
- Tool Call ID/名称/Arguments 非法：Adapter 转换为 `InvalidModelResponseException`。
- 未知工具：生成安全 `ERROR` Tool Result，循环继续。
- 工具抛出异常：生成安全 `ERROR` Tool Result，循环继续。
- 超过最大模型调用次数：`ToolLoopLimitExceededException`，不提交。
- 模型、Tool Loop 或数据库失败：`TURN_FAILED` 只记录稳定错误码，不记录异常消息。

公开 HTTP 第一阶段把 `ToolLoopLimitExceededException` 归为模型无法完成请求，映射为 `502`；不新增公开响应字段。

## 6. 测试设计

- Kernel：消息、工具定义、调用、结果和生命周期不变量。
- Application：Fake Model + Fake Tool 验证直接回答、单/多工具、未知工具、异常、非法响应、迭代上限、提交与事件顺序。
- Adapter：Spring AI Prompt 的工具定义/Tool Message 映射，以及响应 Tool Call JSON 解析。
- Bootstrap：配置默认值、`current_time` 工具和装配。
- Compat：读取 Tool Golden，验证消息共同投影和最小循环轨迹。

## 7. 安全与回退

- 生产注册表只接受 `READ_ONLY`，因此本阶段不会扩大工作区写入面。
- Tool Arguments、Result、Prompt 和消息正文不进入 Lifecycle 或安全日志。
- 工具能力可通过空注册表回退；没有工具定义时行为退化为原被动聊天单次模型调用。
- SQLite Schema 和 HTTP Contract 不变，回退版本仍能读取本阶段产生的数据。

## 8. 验收标准

- Contract、Spec、Plan 使用中文并标记批准状态。
- Python Tool Message Golden 可重复生成且 Manifest Hash 正确。
- Java Tool Golden 覆盖规定 Case。
- 最小 Tool Loop 由项目 Application 层控制，Spring AI 不执行真实工具。
- HTTP 被动聊天兼容，数据库仍只提交最终 `user/assistant` 轮次。
- 默认、Failure、Compat 和架构门禁全部通过。
- 不提交 Secret、真实配置、数据库、Workspace 或日志。
