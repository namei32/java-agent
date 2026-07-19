# R12-S1 只读 Skill Catalog 实施计划

- 状态：S0–S6 已完成并验证
- 分支：`agent/r12-skill-catalog`（R12 专用 worktree；未推送、未合并）
- 前置：[2026-07-19 完成度审计](../architecture/2026-07-19-akashic-java-completion-audit.md)

1. **S1 Fixture/Kernel。** 已固定 13 个 Java-owned Catalog Case、严格 ID、来源、描述符、安全正文和 Disabled Port。
2. **S2 Read-only Adapter。** 已以临时 Root 验证 UTF-8/frontmatter、Workspace 覆盖、稳定排序、Fake requirements、
   大小/链接/越界拒绝和零写入。
3. **S3 Prompt 投影。** 已固定 XML 转义、无路径投影、always 正文剥离 frontmatter、code-point 预算和
   `AKASHIC_CORE` 放置。
4. **S4 Bootstrap。** 已添加默认 `DISABLED` Properties；验证 Disabled 不读取根/PATH/环境，READ_ONLY 才按
   显式 Java Root 装配。
5. **S5 纵向 Chat。** 已以临时 Skill Root 和 Fake Model 验证 System Catalog、Context Frame active 正文和
   `MINIMAL` 零注入；不注册 Tool。
6. **S6 门禁与文档。** 已运行聚焦、默认、`failure`、`compat`、格式/依赖/Secret/Workspace 审计，并用实际证据
   更新矩阵、Roadmap 和本计划。

验证记录：先观察 S1 Kernel、S2 Adapter 的编译 RED，再以最小实现转 GREEN；随后聚焦 default/failure/compat
测试和 Reactor 完整 `clean verify`、`-Pfailure verify`、`-Pcompat verify` 均通过。该记录只证明 S1，不能
作为 R12 后续按需正文、远程 MCP 或 Plugin 生命周期的授权，也不把 Skill 文本的动作要求视为 Capability 授权。

禁止：Python 目录 import、Skill Runner/脚本执行、Shell、网络、Tool 注册、用户 Workspace 写入、真实密钥或前端。
