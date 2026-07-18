# R10 Prompt 编排、Persona 与预算实施计划

- 状态：已实现并验证
- 前置：R7–R9 已合入 `main` 并通过阶段门禁

1. P1：已完成 Java-owned Fixture、Kernel Section/Turn/Budget/稳定码 Contract。
2. P2：已完成确定性 Section 排序、System/Frame 消息边界与不可变结果。
3. P3：已完成 code point/token 预算、固定裁剪计划与模型调用前拒绝。
4. P4：已完成 classpath Akashic Core Persona、固定时钟 envelope、严格 Prompt Properties。
5. P5：已完成旧 `ChatCommand` 双参数构造器兼容、HTTP/CLI/Telegram 的可信 Turn Context 与现有只读 Memory Context 接线；不记录 Prompt 正文。
6. P6：已完成默认/`failure`/`compat` 门禁、架构审查和阶段完成记录。

## 验证记录

- `./mvnw --batch-mode --no-transfer-progress clean verify`：通过。
- `./mvnw --batch-mode --no-transfer-progress -Pfailure verify`：通过。
- `./mvnw --batch-mode --no-transfer-progress -Pcompat verify`：通过；包括 Prompt Fixture 的 Manifest SHA-256、
  Java Contract Evidence 与 Python 基线哈希校验。

阶段结论：R10 已完成版本化 Prompt 编排基础。它不等同于完整 Akashic Agent：Skill Catalog/执行、动态 Persona、
真实 Workspace 输入、未迁移的完整 Tool 路由和任何真实副作用仍受后续 R11–R15 的独立 Contract 约束。

禁止：读取 Python 运行时、执行 Skill、自动读任意 Workspace 文件、修改 Memory、真实 Provider/网络/Telegram、
Side Effect Tool、前端或生产切换。P6 通过后只完成 Prompt 编排基础；R11 才进入 Tool Catalog、Approval 与
逐工具 Capability Contract。
