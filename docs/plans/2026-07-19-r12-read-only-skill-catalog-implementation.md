# R12-S1 只读 Skill Catalog 实施计划

- 状态：S0 审计与 Contract 已完成；S1 起实施中
- 分支：`agent/r11-tool-capability`（仅在 R12 专用 worktree/分支建立前的 Contract 预备；不合并 R11）
- 前置：[2026-07-19 完成度审计](../architecture/2026-07-19-akashic-java-completion-audit.md)

1. **S1 Fixture/Kernel。** 先固定 Java-owned Catalog Case、严格 ID、来源、描述符、安全正文和 Disabled Port。
2. **S2 Read-only Adapter。** 以临时 Root 测试 UTF-8/frontmatter、Workspace 覆盖、稳定排序、Fake requirements、
   大小/链接/越界拒绝和零写入。
3. **S3 Prompt 投影。** 固定 XML 转义、无路径投影、always 正文剥离 frontmatter、code-point 预算和
   `AKASHIC_CORE` 放置。
4. **S4 Bootstrap。** 添加默认 `DISABLED` Properties；验证 Disabled 不读取根/PATH/环境，READ_ONLY 才按
   显式 Java Root 装配。
5. **S5 纵向 Chat。** 以临时 Skill Root 和 Fake Model 验证 System Catalog、Context Frame active 正文和
   `MINIMAL` 零注入；不注册 Tool。
6. **S6 门禁与文档。** 运行聚焦、默认、`failure`、`compat`、格式/依赖/Secret/Workspace 审计，并用实际证据
   更新矩阵、Roadmap 和本计划。

禁止：Python 目录 import、执行 Skill/脚本、Shell、网络、Tool 注册、用户 Workspace 写入、真实密钥或前端。
