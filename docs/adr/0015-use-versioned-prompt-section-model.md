# ADR-0015：使用项目拥有的版本化 Prompt Section 模型

- 状态：已接受
- 日期：2026-07-18
- 阶段：R10

## 决策

Java 使用项目拥有的 `PromptSection`、位置、priority、预算和 Java-owned Fixture 编排 Prompt；Python 的 Persona
与 Prompt 文件作为语义基准，而非 Java 运行时依赖。System 与 context frame 的位置由 Application 固定，不能由
Plugin、Memory 或 Tool 直接重排。

## 原因与后果

这使 Prompt 顺序、裁剪和失败在不同 Provider 前可测试、可审计，也避免 Python 环境、任意 Workspace 内容或
动态插件隐式进入模型上下文。代价是 Python 的每项 Prompt/Skill 演进都需要显式纳入 Fixture 与 Java 迁移；R10
先只定义只读 Skill Catalog 边界，不把 Skill 文本中的动作要求伪装为 Prompt 自带的执行或授权功能；该边界的
后续澄清见 [ADR-0029](0029-treat-skills-as-instructional-assets-not-an-execution-runtime.md)。
