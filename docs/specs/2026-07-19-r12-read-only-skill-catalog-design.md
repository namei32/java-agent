# R12-S1 只读 Skill Catalog 设计

- 状态：已冻结，实施中
- 日期：2026-07-19
- Contract：[只读 Skill Catalog 契约](../contracts/read-only-skill-catalog.md)
- ADR：[ADR-0020](../adr/0020-use-project-owned-read-only-skill-catalog.md)

## 结构

```text
SkillProperties (DISABLED | READ_ONLY, budgets, roots)
        │
        ▼
SkillCatalogPort ── MarkdownSkillCatalogAdapter
        │                         │
        ▼                         └── temporary/explicit Java Roots only
SkillPromptService ──► MemoryContextService ──► SKILLS_CATALOG / ACTIVE_SKILLS
```

Kernel 只持有 `SkillDescriptor`、`SkillSource`、安全正文和 Port；Application 负责确定 XML 与 active 内容投影；
Workspace Adapter 负责路径、UTF-8、frontmatter、覆盖和依赖检查；Bootstrap 只在 `READ_ONLY` 建立 Adapter。这样
Disabled 可以证明零 I/O，且 Prompt 编译不依赖文件系统。

## 数据流

1. Bootstrap 从 `agent.skills` 严格绑定模式、Root 和预算。
2. `DISABLED` 返回不可枚举的空 Port；不调用 Adapter 构造或环境/PATH 检查。
3. `READ_ONLY` Adapter 固定真实 Root，枚举一层子目录，拒绝链接/越界/大文件，并把 Workspace 结果按名字覆盖
   Builtin。
4. Application 将描述符投影为 XML，检查可用且 `always` 的正文，按预算形成两个 Prompt Section。
5. `MemoryContextService` 仅在 `AKASHIC_CORE` 将两段内容加入已有 Prompt 编译器；其它 Prompt Mode 不观察
   Catalog。

## TDD 分段

S1 Fixture/Kernel RED-GREEN；S2 Adapter 路径/解析/覆盖；S3 Prompt XML/always/预算；S4 Properties/Bootstrap
Disabled；S5 Chat 注入；S6 failure/compat 与完整阶段门禁。每段先写可失败测试，再实现最小生产代码。
