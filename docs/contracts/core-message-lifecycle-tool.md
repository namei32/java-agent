# 核心消息、生命周期与 Tool 契约

- 状态：已批准
- 契约版本：1
- 批准日期：2026-07-13
- Python 参考 Commit：`b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Python 证据：`agent/provider.py`、`agent/tool_runtime.py`、`agent/tools/base.py`、`agent/tools/registry.py`、`agent/core/passive_turn.py`、`bus/events_lifecycle.py`

## 1. 目的

本契约定义 Java 最小 Tool Loop 所使用的核心模型消息、工具定义、工具调用、工具结果和生命周期事件。目标是让 Java 与 Python 在共同投影上保持一致，同时确保 Agent Loop、工具执行、会话提交和失败语义由项目代码控制，不交给 Spring AI 或模型供应商 SDK。

契约只描述第一阶段可观察行为。Python 中的 Hook、Plugin、Tool Search、MCP、多模态结果、流式事件和完整 `tool_chain` 元数据仍是后续能力。

## 2. 第一阶段范围

包含：

- OpenAI-compatible Function Calling 的工具定义、调用和结果关联。
- 单个模型响应包含零个、一个或多个工具调用。
- 同一批工具调用按模型给出的顺序串行执行。
- 工具成功、未知工具和执行异常的稳定结果状态。
- 有界模型迭代、最终文本回答和失败时不提交会话。
- 不包含消息正文、参数或结果正文的安全生命周期轨迹。
- 只读、无外部副作用的内置工具。

不包含：

- 写文件、Shell、网络写入、消息推送等副作用工具。
- 人工审批、权限提升、沙箱、重试和幂等键。
- 工具并行执行、Tool Search、MCP、Plugin Hook 和动态注册。
- 多模态 `content_blocks`、Thinking、流式 Delta 和取消。
- Tool Transcript、`tool_chain` 或 Lifecycle Event 的持久化。

## 3. 核心模型消息

模型请求中的消息角色固定为：

| 角色 | 必需字段 | 规则 |
| --- | --- | --- |
| `system` | `content` | 非空文本 |
| `user` | `content` | 非空文本 |
| `assistant` 最终回答 | `content` | 非空文本，`toolCalls` 为空 |
| `assistant` 工具请求 | `toolCalls` | 至少一个调用；`content` 可以为空或包含阶段文本 |
| `tool` | `toolCallId`、`name`、`status`、`content` | 必须关联此前 Assistant 消息中的调用 |

工具请求的共同投影：

```json
{
  "id": "call-001",
  "name": "current_time",
  "arguments": {}
}
```

约束：

- `id`、`name` 必须非空。
- 同一个模型响应内的调用 ID 必须唯一。
- `arguments` 必须是 JSON Object；空参数规范化为 `{}`。
- 未知 JSON 字段由适配器忽略，不进入 Kernel。
- Assistant 同时返回文本和工具调用时，文本只是阶段内容，不作为最终回答持久化或返回。
- 模型响应既没有非空文本也没有工具调用时，属于 `INVALID_MODEL_RESPONSE`。

## 4. 工具定义与执行结果

工具定义包含：

```json
{
  "name": "current_time",
  "description": "返回当前 UTC 时间",
  "inputSchema": {
    "type": "object",
    "properties": {},
    "additionalProperties": false
  },
  "risk": "READ_ONLY"
}
```

规则：

- 名称匹配 `[A-Za-z0-9_-]{1,64}`，同一注册表内唯一。
- Description 非空。
- `inputSchema` 顶层类型必须是 `object`。
- 第一阶段只接受 `READ_ONLY`；其他风险等级即使未来出现在协议中，也不得注册执行。
- Schema 负责向模型描述参数；第一阶段不实现完整 JSON Schema 校验，具体工具仍必须在执行入口校验参数并安全失败。

工具结果状态固定为：

- `SUCCESS`：工具正常完成。
- `ERROR`：未知工具、参数错误或执行异常。
- `DENIED`、`TIMEOUT`、`CANCELLED`、`SKIPPED`：为后续审批、超时和取消预留；第一阶段不主动产生。

工具结果正文会回送模型，但不得直接作为 HTTP 响应或日志。未知工具和异常必须转换成安全的 `ERROR` 结果，使模型有机会修正或给出最终回答；异常类名、堆栈、密钥和敏感参数不得进入结果。

## 5. 最小 Tool Loop

循环按以下顺序执行：

```text
组装 System + 历史 + 当前 User
  -> MODEL_REQUESTED
  -> 模型返回最终文本：提交并结束
  -> 模型返回 Tool Calls：逐个执行并追加 Tool Result
  -> 继续下一次模型调用
```

详细规则：

1. `maxIterations` 表示一次用户请求允许的模型调用次数，必须大于零。
2. 每次模型调用都携带完整的本轮临时消息和当前只读工具定义。
3. 一批工具调用按响应中的顺序串行执行；一个调用失败不跳过同批剩余调用。
4. 每个调用都追加一条关联的 Tool Result，然后进入下一次模型调用。
5. 得到不含 Tool Calls 的非空 Assistant 文本时结束。
6. 在得到最终文本前耗尽迭代次数，抛出稳定的 `TOOL_LOOP_LIMIT_EXCEEDED`，本轮不提交。
7. 第一阶段不额外调用模型生成“超限总结”。这是相对 Python 当前行为的批准安全差异，避免在预算耗尽后产生未计数调用。

## 6. 生命周期事件

生命周期是只读观察协议，不承担业务控制。事件顺序固定为：

- `TURN_STARTED`
- 每次模型调用的 `MODEL_REQUESTED`、`MODEL_COMPLETED`
- 每个工具调用的 `TOOL_CALL_STARTED`、`TOOL_CALL_COMPLETED`
- 成功路径的 `TURN_COMMITTING`、`TURN_COMMITTED`
- 失败路径的 `TURN_FAILED`

事件共同字段：

- `type`：稳定事件类型。
- `iteration`：模型调用序号，从 `1` 开始；Turn 级事件为 `0`。
- `callId`、`toolName`：仅 Tool 事件提供。
- `status`：Tool 完成或 Turn 失败时提供稳定状态/错误码。

生命周期事件禁止包含：Session 原文、用户消息、Assistant 正文、System Prompt、工具参数、工具结果、API Key、异常消息和堆栈。观察者异常不得改变业务结果；实现必须隔离观察失败。

## 7. 会话提交、失败与并发

- 会话执行闸门覆盖整个 Tool Loop 和最终提交。
- 当前 User、Assistant Tool Call 和 Tool Result 在循环期间只存在于内存。
- 只有最终 Assistant 文本产生后，才原子追加一组 `user/assistant` 持久化消息。
- 模型失败、响应非法、迭代超限或数据库失败时，本轮不得留下会话消息。
- 第一阶段只允许只读工具，因此模型或数据库在工具执行后失败，不存在需要回滚的外部副作用。
- Tool Transcript 暂不写入 SQLite；这是相对 Python `tool_chain` 的明确能力缺口，不得声称已经支持完整工具历史恢复。
- 同一 Session 在单 JVM 内串行，不同 Session 可以并行；不提供跨进程锁。

## 8. Spring AI 边界

Spring AI 只负责：

- 把项目 Tool Definition 映射为供应商 Function Schema。
- 把项目消息映射为 System/User/Assistant/Tool 消息。
- 把供应商 Tool Call 解析为项目类型。

Spring AI Tool Callback 不得执行真实工具。真实工具选择、调用顺序、异常转换、迭代上限和提交都由 `agent-application` 控制。

## 9. Golden 共同投影

Tool Golden 分为两部分：

1. Python Reference：工具 Schema、Assistant Tool Call 和 Tool Result 消息格式，直接调用 Python 生产 helper 生成。
2. Migration Contract：最小循环的稳定生命周期轨迹、结果状态、最终回答和提交决策。

至少覆盖：

- 无工具直接回答。
- 单工具成功后最终回答。
- 同批多工具按顺序执行。
- 未知工具产生 `ERROR` 后恢复。
- 工具异常产生安全 `ERROR` 后恢复。
- 模型响应非法。
- 迭代上限且不提交。

Golden 不包含真实时间、密钥、原始异常、工具参数中的隐私值或模型自由文本。

## 10. 变更审批

以下变化必须修改本契约并重新批准：

- 新增或改变消息角色、工具结果状态或生命周期顺序。
- 允许副作用工具、并行执行、自动重试、审批或跨进程执行。
- 改变迭代计数、未知工具、失败或会话提交语义。
- 持久化 Tool Transcript 或把工具结果暴露到 HTTP。
- 让 Spring AI、MCP SDK 或 Plugin 取得循环控制权。
