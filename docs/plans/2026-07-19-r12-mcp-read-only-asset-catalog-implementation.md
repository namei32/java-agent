# R12-S2 MCP 只读 Assets Catalog 实施计划

- 状态：S1–S5 已完成并验证
- 分支：`agent/r12-skill-catalog`
- 前置：[R12-S2 Contract](../contracts/mcp-read-only-asset-catalog.md)

1. **S1 Kernel/Fixture。** 已固定 13 个 Java-owned Case、严格 mode、descriptor、稳定名称和 Disabled 零调用。
2. **S2 Discovery。** 已扩展本地 Java Reference Server 与 SDK Gateway，验证 capability gate、分页和空 Catalog。
3. **S3 Hardening。** 已增加 UTF-8、字段/条目/Cursor 预算、重复、Stale 和单 Server 失败隔离 RED/GREEN。
4. **S4 Bootstrap。** 已增加默认 `DISABLED` 的 Properties；验证禁用时不新建 Assets 能力、不增 list 调用。
5. **S5 Gates/Docs。** 已运行聚焦 default/compat 及完整 `clean verify`、`-Pfailure verify`、`-Pcompat verify`；
   以实际结果回写 Contract、矩阵、Roadmap 与本计划。

验证记录：先以缺少 Kernel/Runtime/Bootstrap 类型的编译失败建立 S1、S2、S4 RED；随后以同一聚焦命令转 GREEN。
审查还发现 Prompt 总数会在 Resource 为空时突破每类 32 项上限，已补失败测试并按 kind 计数修复。阶段末三套完整
Reactor 门禁均通过；没有运行真实 MCP Server、网络、Python、Secret 或用户数据。

禁止：Resource/Prompt 正文读取、Prompt 注入、Tool 注册、网络/HTTP、动态 Server、Python import、真实 Secret、
真实 Server、用户 Workspace 写入和生产启用。
