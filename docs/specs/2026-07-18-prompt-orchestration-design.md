# R10 Prompt 编排、Persona 与预算设计

## 1. 分层

Kernel 定义不可变 `PromptSection`、`PromptSectionId`、`PromptPlacement`、`PromptTurnContext`、预算、裁剪计划、
结果与稳定码；它不读取资源、工作区或时钟。Application 的 `PromptOrchestrator` 接收已渲染的 Section 与选中历史，
验证顺序、生成 frame、执行整体裁剪并输出 `ModelMessage`。Bootstrap 读取 classpath Prompt 资源、绑定严格
Properties，并将 `Clock`/`ZoneId`/安全 channel-session 元数据传入 Application。Workspace Adapter 仅复用已有
Memory Profile Port；Skill Catalog 在 R10 先以受限只读 Port 表示。

## 2. 正确性与安全

所有 Section 在进入模型前必须有已知 ID、固定位置、有限 code point 数和明确 source。编排先复制输入、再排序、
再做预算；不修改调用方历史，也不将裁剪结果写回 Memory。每次 Context Frame 都是单独 `USER` 消息且有固定
安全说明。任何未知/重复 ID、位置不符、排序碰撞、空当前用户、无效时间上下文或无法满足预算都在模型调用前失败。

`AKASHIC_CORE` 的资源文本版本随 Java-owned Fixture 固定，而不是运行时导入 Python。这样保留 Python 基线的
可观察顺序，同时不会让 Java 运行依赖 Python 文件、解释器、用户插件或未验证的 Workspace 文本。

## 3. 接线与兼容

现有 `ContextAssembler` 成为 `PromptOrchestrator` 的唯一调用点；`MemoryContextService` 继续负责只读 Profile
与检索，再将结果作为两个 Section 传入。`ChatCommand` 在保留双参数构造器兼容的前提下接受可选 Prompt Turn
上下文；HTTP、CLI 和 Telegram 显式提供各自 channel，未知来源保持 `unknown`。`MINIMAL` 的资源和输出与当前
system prompt 路径一致，避免未经启用的部署行为变化。

## 4. TDD 切片

P1 Fixture/Kernel Contract；P2 排序与 frame 编排；P3 预算/裁剪/拒绝；P4 资源、时间与 Turn Context；P5
Memory/Chat/HTTP/CLI/Telegram 接线；P6 默认关闭兼容、故障与完整阶段门禁。所有测试使用固定 `Clock`、临时
目录和内存 Port，不访问 Provider、网络或用户工作区。
