# R11-B3 只读 Workspace Tools 实施计划

- 状态：S1–S6 已完成；完整三套 Maven 门禁已通过
- 分支：`agent/r12-skill-catalog`
- 前置：[R11-B3 Contract](../contracts/read-only-workspace-tools.md)

1. **S1 Fixture/Tool 定义。** 已固定 Java-owned Case、严格 Mode、稳定码、两个 `READ_ONLY` Definition 和 Disabled
   零 Tool。
2. **S2 Root/Path Adapter。** 已用临时 Root RED-GREEN 验证相对路径、逐级真实路径、链接/越界/特殊文件拒绝；不写文件。
3. **S3 内容/目录投影。** 已验证严格 UTF-8、二进制、行号、offset/limit、400 行/10KB/20K code-point、目录预算和
   Unicode code-point 稳定排序。
4. **S4 Bootstrap/Catalog。** 已添加默认 `DISABLED` Properties、显式 Root 校验、Builtin Deferred 注册与全局 Tool
   Mode 边界；部署只可收紧预算。
5. **S5 纵向运行时。** 已用 Fake Model 证明先 `tool_search`、下一请求出现 Schema、再执行临时 Root Tool；失败不留下
   可见性状态或 Conversation 部分提交。
6. **S6 Gate/Docs。** 已通过聚焦 default/failure/compat、Fixture Manifest、Spotless、`clean verify`、
   `-Pfailure verify`、`-Pcompat verify`；已回写契约、矩阵、Roadmap、审计与本计划。

禁止：真实 Workspace、Root 回退、Python import、图像、写入/编辑、Shell、网络、MCP、递归、后台扫描、Secret 和
生产启用。
