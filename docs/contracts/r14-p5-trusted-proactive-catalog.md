# R14 P5 受信主动 Capability Catalog 契约

- 阶段：R14 P5
- 状态：已实现为未接线的静态 Catalog 表面；默认仍 `DISABLED`
- 日期：2026-07-19
- 关联 ADR：[ADR-0041](../adr/0041-restrict-r14-p3-p5-to-approved-local-fakes.md)
- 前置：P1–P4 已完成 Contract；P3/P4 仅在本地 Fake 边界获批

## 1. 唯一 Catalog 表面

P5 只静态定义两个 `DEFERRED` Builtin 请求 Schema：

| Tool | 版本 | 风险 | Schema |
| --- | --- | --- | --- |
| `request_proactive_memory_capture` | `r14-proactive-memory-v1` | `WRITE` | `{}`，不接受正文、Scope、类型、ID、Session、审批或幂等键。 |
| `request_local_fake_peer_task` | `r14-local-fake-peer-v1` | `EXTERNAL_SIDE_EFFECT` | `{}`，不接受 peer、URL、命令、路径、环境、Task 正文、预算或审批字段。 |

它们只能在当前 Turn 成功调用 `tool_search` 后，于下一轮模型请求出现。默认空 Toolset 不注册 Catalog 条目；即使在
`catalogOnly()` 测试构造中，Placeholder 也必须返回固定“工具不可用”，不能创建 Pending、Approval、Capsule、Anchor、
线程、数据库或 Port 调用。

## 2. 前置与未来接线

P5 的静态 Schema 不等于执行权。未来 Producer 必须分别证明 P3/P4 Capability、固定配置、Approval、加密 Capsule、
Reservation、Ledger、Anchor、Recovery 和默认关闭装配均健康；任何一个前置缺失时零 Catalog 注册。P5 不增加 Spring
Bean、配置键、HTTP/CLI/控制面 Route、Plugin 动态注册、MCP 注入、后台自动执行或重试。

## 3. 实现证据与验收

`r14-trusted-proactive-catalog-v1` 固定 9 个场景，由 `TrustedProactiveRequestToolset` 消费。它只提供
`disabled()` 的空 Toolset 和仅供 Catalog 测试使用的 `catalogOnly()`；后者的两个 Placeholder 无论传入何种参数都返回
“工具不可用”，且没有 Producer、Pending、Approval、Capsule、Anchor、线程、数据库或 Port 依赖。

Fixture 必须验证默认隐藏、Deferred 搜索解锁、Schema 精确性、风险/版本、搜索不泄漏敏感字段、Placeholder 零副作用和
与既有 `forget_memory` Catalog 的共存。没有真实网络、Peer、Memory Mutation 或 Delivery。
