# 项目文档导航

本目录记录 Namei Agent Java 的架构约束、迁移路线、行为契约、已批准设计、实施计划和运行手册。Python 仓库是兼容性基准，但 Java 项目的有效决策以本目录和根目录 `AGENTS.md` 为准。

## 当前状态

- 已完成：五模块 Maven 骨架、同步 HTTP 被动聊天、历史恢复、OpenAI-compatible 模型适配、SQLite 原子轮次写入、失败语义、单进程同会话串行化、健康检查与基础可观察性。
- 已完成：MVP Minor 技术债加固和 Java 重写文档治理。
- 已完成：被动聊天共同投影的 Python/Java Golden Test 规范、历史、Prompt、SQLite、错误映射基准及 CI 门禁。
- 已完成：Python/Java 配置兼容 Contract、配置 Golden、TomlJ Parser 选型、只读 Resolver、Spring Boot 启动装配和无副作用检查入口。
- 已完成：核心消息、生命周期和 Tool Contract，Tool Message/最小循环 Golden，以及带 `current_time` 的只读最小 Tool Loop。
- 已完成：Tool Runtime 安全契约实现，包括模式、预算、Schema、Arguments/Result 边界、超时、并发许可、取消和安全 Golden。
- 下一里程碑：审批、副作用、幂等和沙箱 Contract/Spec/Plan 草案已形成，下一步是评审批准；不能直接跳到实现或完整工具生态扩展。
- 尚未覆盖：副作用工具、审批、流式输出、MCP、长期记忆、渠道、插件、主动任务、Drift 和 Subagent。

完整进度与阶段门禁见 [Java 重写 Roadmap](roadmap/java-rewrite-roadmap.md)，逐项能力状态见 [Python/Java 能力差距矩阵](architecture/python-java-capability-matrix.md)。

## 阅读顺序

1. [Java 重写指南](architecture/java-rewrite-guide.md)：目标架构、迁移原则和兼容策略。
2. [Java 重写 Roadmap](roadmap/java-rewrite-roadmap.md)：阶段顺序、交付物和退出门禁。
3. [Python/Java 能力差距矩阵](architecture/python-java-capability-matrix.md)：当前覆盖范围与缺口。
4. [架构决策记录](adr/README.md)：已经固定的关键技术决策。
5. 与当前变更相关的 `contracts/`、`specs/` 和 `plans/`。
6. [本地开发运行手册](runbooks/local-development.md)。

当前 Golden 资产从 [Python/Java Golden Test 夹具规范](contracts/golden-test-fixtures.md)进入，包含配置、历史、Prompt、SQLite、错误映射、Tool Loop 和 Tool Runtime 安全基准。
配置迁移边界从 [Python/Java 配置兼容契约](contracts/python-java-configuration.md)进入，实际启动与检查命令见[本地开发运行手册](runbooks/local-development.md)。
工具迁移边界从 [核心消息、生命周期与 Tool 契约](contracts/core-message-lifecycle-tool.md)进入。
Tool Runtime 的模式、预算、校验、超时、取消和 Provider 发布门禁以 [Tool Runtime 安全契约](contracts/tool-runtime-safety.md)为准。
R3.2 的待评审边界从 [Tool 审批、副作用、幂等与沙箱安全契约](contracts/tool-approval-side-effect-safety.md)进入，候选实现见 [Tool Approval Framework 设计](specs/2026-07-14-tool-approval-framework-design.md)和[实施计划](plans/2026-07-14-tool-approval-framework-implementation.md)。这些文档仍为草案，不授权任何真实副作用工具。

## 目录职责

| 目录 | 内容 | 是否描述当前事实 |
| --- | --- | --- |
| `architecture/` | 总体架构、迁移指南、能力盘点 | 是 |
| `roadmap/` | 跨里程碑计划和阶段门禁 | 是 |
| `adr/` | 重要且长期有效的架构决策 | 是；被替代时显式标记 |
| `contracts/` | 外部 API、事件和数据兼容契约 | 是 |
| `specs/` | 单项功能或治理设计 | 以文首状态为准 |
| `plans/` | 可执行任务、验证证据和历史记录 | 以文首状态为准 |
| `runbooks/` | 开发、启动、排障和运维步骤 | 是 |

## 文档状态规则

- `草案`：仅供讨论，不能据此实现有兼容性影响的行为。
- `已批准`：可以进入实施。
- `实施中`：已经开始落地，完成情况以任务清单为准。
- `已实现并验证`：实现和规定门禁均通过。
- `已废弃`：不得继续作为实施依据，并必须指向替代文档。

已经完成的 Spec 和 Plan 保留为决策与验证历史，不把它们改写成 Roadmap。Roadmap 只维护阶段级事实；具体行为仍以 Contract、Spec 和 ADR 为准。
