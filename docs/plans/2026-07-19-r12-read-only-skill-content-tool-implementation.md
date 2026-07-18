# R12-S4 按需只读 Skill 正文 Tool 实施计划

- 状态：已实现并验证
- 分支：`agent/r12-skill-catalog`
- 前置：[R12-S4 Contract](../contracts/read-only-skill-content-tool.md)

1. **C1 Fixture/Kernel（完成）。** 固定 Tool Schema、参数、稳定错误码、正文、名称一致性与预算 Case；以 RED/GREEN
   扩展无路径 `SkillCatalogPort.readAvailable` 与不可变内容模型。
2. **C2 Adapter（完成）。** 复用 S1 的 Root/链接/UTF-8/frontmatter/覆盖规则，验证可用性、无效候选、预算与候选变化
   后的安全回退。
3. **C3 Tool/Catalog（完成）。** 以严格 `ReadSkillTool` 实现参数/预算/错误投影，作为 deferred Tool 接入当前 Turn 的
   `tool_search`；未解锁调用被既有 Catalog 可见性拒绝。
4. **C4 Bootstrap（完成）。** 添加严格 Properties、双重 `READ_ONLY` 注册条件、Disabled 零注册及 Fake Model 的完整
   Tool Loop 回送测试；非 `READ_ONLY` Tool Mode 保留 S1 Prompt 能力但不注册 Tool。
5. **C5 Gate/Docs（完成）。** 已更新 Golden Manifest、完成度审计、能力矩阵、Roadmap、README 和 R12 总计划；聚焦及完整
   `clean verify`、`-Pfailure verify`、`-Pcompat verify` 均通过。

禁止：执行 Skill、返回路径或 frontmatter、通用文件读取、目录枚举、网络、Shell、Python import、动态下载、写入、
Gate 或生产启用。
