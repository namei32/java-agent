# ADR-0003：将 Spring AI 限定在模型适配器边界

- 状态：已接受
- 日期：2026-07-12

## 背景

Spring AI 可以降低模型供应商接入成本，但其 ChatModel、Message 和 Tool Callback 类型若进入核心层，会让 Agent Loop 的控制权和测试边界依赖框架。

## 决策

Spring AI 仅存在于 `adapter-spring-ai` 和 `agent-bootstrap` 的装配层。`agent-kernel` 定义项目自己的 `ChatModel` Port、消息和请求响应模型；工具循环、审批、上下文预算、会话提交和重试语义由项目代码控制。

OpenAI-compatible 服务通过配置注入 `base-url`、`api-key` 和 `model`。切换模型 SDK 只能影响适配器及装配，不得改变核心协议。

## 结果

核心逻辑可使用纯 Java Fake 测试，供应商切换成本较低；代价是需要维护明确的协议映射，不能直接把 Spring AI 的高级 Agent 抽象当作运行时。
