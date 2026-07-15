# ADR-0006：采用官方 MCP Java SDK 并自持有界 stdio Transport

- 状态：已接受
- 日期：2026-07-15
- 批准记录：2026-07-15，用户要求开始实现 MCP 只读客户端纵向切片
- 关联 Contract：[MCP 只读客户端与 Tool Runtime 契约](../contracts/mcp-client-tool-runtime.md)
- 关联 Spec：[MCP 只读客户端纵向切片设计](../specs/2026-07-15-mcp-read-only-client-design.md)

## 背景

R5.1 需要实现 MCP 初始化、能力和版本协商、分页 Tool 发现、Request ID 关联、Tool 调用与通知。继续沿用 Python 项目中的手写单通道 JSON-RPC 循环，会复制协议解析、错误 ID、并发请求、生命周期和关闭缺陷，也会让 Java 主线长期维护一套不完整协议实现。

官方 MCP Java SDK `2.0.0` 已正式发布并对齐稳定 MCP `2025-11-25`。它提供 Client、生命周期、Schema、Jackson 3 Mapper、请求关联与 Tool API，许可证为 MIT。但 M0 源码 Spike 同时确认其内置 `StdioClientTransport` 不满足本项目的安全 Contract：

- `ServerParameters` 默认继承 HOME、PATH、SHELL 等变量。
- Transport 在现有 Process Environment 上追加变量，没有先清空。
- Inbound 使用无界 `BufferedReader.readLine()`，不能在反序列化前实施 Wire 字节上限。
- Client Session 的 `Mono` 被取消时不发送 MCP `notifications/cancelled`。
- 内置关闭直接 TERM，不能表达本项目要求的 stdin EOF、TERM、KILL 与统一 Shutdown Deadline。

## 决策

采用 `io.modelcontextprotocol.sdk:mcp:2.0.0`，但只复用官方 SDK 的 Async Client、生命周期、协议 Schema、Request ID 关联和 Jackson 3 Mapper。`adapter-mcp` 自行实现 SDK `McpClientTransport` 接口的有界 stdio Transport。

自有 Transport 必须：

- 清空 Process Environment 后只复制显式 Allowlist。
- 在 UTF-8 解码和 JSON 反序列化前逐字节实施消息上限。
- 在写入前实施出站消息上限。
- 拦截准确的 `tools/call` Request ID，使外层取消能发送 Wire `notifications/cancelled`。
- 按 stdin EOF、TERM、KILL 的有界顺序回收进程、流和执行任务。
- 只存在于 Adapter，不把 Reactor、SDK、Process 或 JSON-RPC 类型暴露给 Kernel/Application/Bootstrap 公共 API。

SDK 版本使用父 POM Property 精确锁定，不使用 Snapshot、版本范围或 Spring AI MCP Starter。

## 备选方案

| 方案 | 优点 | 拒绝原因 |
| --- | --- | --- |
| 继续手写完整 MCP Client | 完全可控、无 Reactor | 协议面过大，需自行维护生命周期、请求关联与版本演进 |
| 直接使用 SDK `StdioClientTransport` | 实现量最小 | 环境、Wire、Cancellation 和关闭不满足已批准 Contract |
| Spring AI MCP Starter | Bootstrap 简单 | 自动注册与 Spring 类型会模糊 Tool Runtime 和 Adapter 所有权 |
| 只使用 SDK Core 并手写 Session | 可绕过 Client 缺口 | 重复实现初始化、能力、请求关联和通知，价值低于自定义 Transport |
| 不接入 MCP | 风险最低 | 无法完成已批准的 R5.1 互操作目标 |

## 后果

- 协议模型和生命周期随官方 SDK 演进，项目不复制完整 MCP Schema。
- Reactor、Jackson 2 Annotations、Jackson 3、NetworkNT Validator 等传递依赖被限制在 `adapter-mcp`。
- 项目需要维护一份小而安全敏感的 stdio Transport，并用 Wire/Process Integration Test 覆盖其边界。
- SDK 升级不能只改版本号；必须重新审计内置 Cancellation、环境、Wire、分页和关闭行为，并重跑 Contract Fixture。
- 如果未来官方 Transport 提供等价边界，可通过新 ADR 和相同测试替换自有实现。
- Wire Cancellation、迟到响应丢弃和进程回收测试已在 R5.1 通过；生产与模板仍保持 `DISABLED`，直到具体真实 Server、Secret、网络、数据和部署范围另行获批。
