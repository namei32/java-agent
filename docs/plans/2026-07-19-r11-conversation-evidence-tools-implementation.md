# R11-B4 当前会话只读证据 Tool 实施计划

- 状态：E1–E5 已完成并验证
- 前置：[R11-B4 Contract](../contracts/read-only-conversation-evidence-tools.md)

1. **E1 Fixture/Kernel RED → GREEN（完成）。** 已添加 `conversation-evidence-v1` Fixture、Manifest 与 Contract Test；
   已建立 Evidence model/Port、严格 opaque ID 与查询规则。
2. **E2 SQLite RED → GREEN（完成）。** 已在临时 `sessions.db` 上实现当前 Key 的 Fetch/窗口/Search/分页；验证预编译
   查询、角色/顺序、跨 Session 零泄漏、候选数上限、分页和 Store 故障。
3. **E3 Invocation Context RED → GREEN（完成）。** 已为 Application 的 ContextualTool 加显式 Context 传播；普通 Tool
   保持旧 API，Evidence Scope 不进入模型参数、Plugin 或 MCP。
4. **E4 Tool/Bootstrap RED → GREEN（完成）。** 已实现 JSON 投影、deferred `tool_search` 解锁、严格 Properties/default
   disabled，并以真实 Tool Loop 验证 Search → Fetch → final 文本。
5. **E5 Failure/Compat/Docs（完成）。** 已覆盖结果预算、宽窗口安全失败、非法 ID/参数、Store 故障和 Fixture 上限，
   并回写矩阵、Roadmap 与审计。`./mvnw spotless:check`、`./mvnw clean verify`、`./mvnw -Pfailure verify` 与
   `./mvnw -Pcompat verify` 均已通过。

禁止：跨 Session、原始 Session ID、真实用户 DB、全文索引、写入、Citation 强制、Memory Tool、网络和前端。
