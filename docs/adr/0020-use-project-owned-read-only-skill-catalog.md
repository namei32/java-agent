# ADR-0020：使用项目拥有的只读 Skill Catalog

- 状态：已接受
- 日期：2026-07-19
- 决策者：Java Rewrite 主线

## 背景

Akashic 的 `SkillsLoader` 会组合 Workspace 与 builtin `SKILL.md`，检查依赖，并把目录摘要和 always Skill 正文
加入 Prompt。Java Prompt Section 已有 `SKILLS_CATALOG`、`ACTIVE_SKILLS`，但没有相应运行时；直接加载 Python
目录或执行 Skill 文本会让 Java 运行依赖未受控的解释器、路径和副作用。

## 决策

Java 定义自有 Kernel Skill 模型/Port 与 Workspace Adapter。Catalog 默认 `DISABLED`；`READ_ONLY` 只扫描显式
Java Roots 的直接 `<name>/SKILL.md`，使用严格路径、大小和 frontmatter 规则。模型只见无路径的安全摘要，且
只在 `AKASHIC_CORE` 时获得可用 always 正文。需求检查是注入式、只读存在性查询，不执行命令或回显环境值。

Workspace 同名覆盖 Builtin，排序和预算均确定；无效或不安全候选被局部拒绝，不能放宽根目录或使 Prompt 失败。

## 后果

获得 Python Skills 的发现/覆盖/可用性/always Prompt 语义，而不需要 Python runtime 或新 Tool。Java 不能仅靠
Catalog 加载按需正文或执行 Skill；这会保留为后续显式 Contract。生产默认不读任何 Skill Root。
