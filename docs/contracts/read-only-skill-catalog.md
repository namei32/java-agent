# R12-S1 只读 Skill Catalog 契约

- 阶段：R12-S1
- 状态：已冻结，实施中；默认 `DISABLED`
- 日期：2026-07-19
- Python 证据：`agent/skills.py`、`agent/core/prompt_block.py`、`skills/*/SKILL.md`
- 关联 ADR：[ADR-0020：使用项目拥有的只读 Skill Catalog](../adr/0020-use-project-owned-read-only-skill-catalog.md)
- 设计：[R12-S1 只读 Skill Catalog 设计](../specs/2026-07-19-r12-read-only-skill-catalog-design.md)
- 计划：[R12-S1 只读 Skill Catalog 实施计划](../plans/2026-07-19-r12-read-only-skill-catalog-implementation.md)

## 1. 目标与非目标

本切片迁移 Python Skills 的安全子集：发现 `SKILL.md`、Workspace 覆盖 Java-owned builtin、frontmatter 中的
名称/描述/`always`/`requires`、确定排序、可用性投影及 Prompt 中的 Catalog/always 正文。它只读取显式根目录，
绝不执行 Skill 文本、脚本、CLI、MCP、Tool、网络或写入任何文件。

按需加载完整 Skill、模型可调用的 `read_skill` Tool、Skill 内脚本/引用、动态下载、用户 Workspace 写入和
Python Skill 的进程内 import 均不属于 S1。它们不能以读取 Catalog 为由自动启用。

## 2. 模式、根目录与零资源默认

`agent.skills.mode` 只能是严格大写值：

| 模式 | 行为 |
| --- | --- |
| `DISABLED` | 默认；不读取、创建或枚举 Workspace/Builtin 根，不检查 PATH/环境，不向 Prompt 添加 Section。 |
| `READ_ONLY` | 只读取显式 Java Skill Roots；Workspace Root 的同名 Skill 覆盖 builtin。 |

每个 Root 只接受直接子目录 `<name>/SKILL.md`。根目录不存在时等价于空 Catalog，且不得创建它。所有路径须在
真实 Root 内；符号链接、非普通文件、越界、不可读或超过预算的候选稳定忽略。运行时模型、日志、HTTP/SSE 和
Fixture 不回显物理路径、Skill 正文、环境变量值或 PATH。

## 3. Skill 语义与安全投影

`name` 必须与目录名相同并匹配 `^[a-z][a-z0-9-]{0,62}$`。`description` 是必填、非空、规范化的单行公开摘要；
`metadata` 仅接受 JSON 对象中的 `akashic` 或 `skill` 子对象。未知 metadata 字段不能提高权限。

`always` 仅接受布尔 `true`。`requires.bins`、`requires.env` 只接受去重的有效标识符数组；它们只决定
`available`，不会运行命令、读取环境变量值或将缺失名称外泄到模型。目录与来源按名字稳定排序，Workspace
优先于 Builtin；重复 builtin 或同 Root 的重复/无效名称均不产生不确定结果。

公开 `SkillDescriptor` 只含版本、名称、描述、来源与 `available`。Catalog Prompt 使用确定 XML，文本被 XML
转义；它没有文件位置。`always && available` 的正文以 `ACTIVE_SKILLS` Context Frame 注入，保留原始正文但
剥离 YAML frontmatter，并以总/单项 code-point 预算裁剪。不可用、无效或超限 Skill 永不注入。

## 4. Prompt 与错误边界

启用 `READ_ONLY` 时，非空 Catalog 写入现有 `SKILLS_CATALOG` System Section；非空 always 内容写入现有
`ACTIVE_SKILLS` Context Frame。`MINIMAL` Prompt 模式保持零 Section；`AKASHIC_CORE` 才可使用 Skill Section。
Catalog 失败、路径违规、frontmatter 无效、预算超限或重复不会让 Agent 降级为任意文件读取：该候选被忽略，
其余有效候选继续按照确定顺序投影。

## 5. 验收与暂停

Java-owned Fixture 必须固定 Disabled 零 I/O、来源覆盖、排序、frontmatter、依赖可用性、XML 转义、always
正文、预算、符号链接/越界拒绝和 Prompt 放置。测试只创建临时 Java Roots 与 Fake PATH/环境查询。

在实现按需 Skill 正文、`read_skill` Tool、脚本或任何真实资源之前，先冻结新的 Tool/Workspace/执行 Contract；
R12-S1 全部门禁通过也不授予这些权限。
