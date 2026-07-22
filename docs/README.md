# 项目文档导航

本目录只保留实现、审查和运行 Java Agent 所需的长期权威文档。Python 项目是行为对照，Java 项目的有效约束以根目录 `AGENTS.md` 和本目录中的 Contract、ADR、Spec 为准。

阶段性任务清单、RED/GREEN 记录和临时实现说明不再长期保存在 `docs/plans/`；它们由当前任务、Git 提交和 PR 承载。跨功能的当前进度统一更新到 Roadmap，避免完成后的计划文档继续与代码争夺事实来源。仅被版本化 Golden Fixture 直接引用的少量历史计划作为兼容证据保留，不能再作为当前进度来源。

## 阅读顺序

1. [Java 重写指南](architecture/java-rewrite-guide.md)：目标架构、模块边界和迁移原则。
2. [Python/Java 能力差距矩阵](architecture/python-java-capability-matrix.md)：已经覆盖的能力和剩余差距。
3. [Java 重写 Roadmap](roadmap/java-rewrite-roadmap.md)：阶段级状态和下一批功能。
4. 与当前功能直接相关的 Contract、ADR 和 Spec。
5. [本地开发运行手册](runbooks/local-development.md)：启动、验证和排障命令。

## 目录职责

| 目录 | 长期职责 | 何时更新 |
| --- | --- | --- |
| `architecture/` | 总体架构、迁移原则和能力矩阵 | 模块边界或总体迁移策略改变时 |
| `roadmap/` | 阶段级进度、优先级和剩余能力 | 一个完整功能切片验收后 |
| `contracts/` | 可观察行为、协议、状态、失败与安全边界 | 行为契约改变前 |
| `adr/` | 难以逆转的架构决策及其理由 | 作出长期技术决策时 |
| `specs/` | 已批准功能设计和版本化 Fixture 的设计依据 | 实现复杂功能或改变既有设计时 |
| `runbooks/` | 可执行的开发、演练、回退和排障步骤 | 操作方式改变时 |
| `plans/` | Golden Fixture 仍直接引用的历史兼容证据 | 只允许随对应 Fixture 迁移或删除 |

## 单功能开发方式

每次只选择一个可独立验收的用户功能：

1. 从能力矩阵和 Roadmap 确认目标、非目标和安全边界。
2. 复用现有 Contract/ADR/Spec；只有契约、架构或高风险语义改变时才新增或修改文档。
3. 在当前任务中列出小步实现项和聚焦测试，不创建永久实施计划文件。
4. 按 RED、GREEN、自审完成实现；小任务只运行聚焦测试。
5. 完整功能验收后，更新 Roadmap、能力矩阵及受影响的权威文档；阶段门禁按 [轻量 Vibe Coding 工作流](vibe-coding-workflow.md)集中执行。

## 维护规则

- 同一事实只保留一个权威来源：行为看 Contract，决策看 ADR，设计看 Spec，阶段进度看 Roadmap，操作看 Runbook。
- 已完成任务的执行历史从 Git/PR 查询，不在 `docs/` 中累积日报、审查包、实现报告或逐阶段计划。
- Contract、ADR 或 Spec 若被 `testdata/golden` 的 `contractEvidence` 引用，不得在未同步更新并审查 Golden Fixture 的情况下删除或改名。
- 文档必须描述当前代码和有效边界；发现过期状态时直接修正权威文档，不再追加一份新的汇总文档。
- 真实网络、Secret、用户数据、外部副作用和生产部署仍必须遵守对应 Contract 与 `AGENTS.md` 的独立授权要求。

## 常用入口

- [核心消息、生命周期与 Tool 契约](contracts/core-message-lifecycle-tool.md)
- [Tool 审批、副作用、幂等与沙箱安全契约](contracts/tool-approval-side-effect-safety.md)
- [Java 原生语义记忆契约](contracts/semantic-memory-persistence-optimizer.md)
- [MCP 只读客户端契约](contracts/mcp-client-tool-runtime.md)
- [版本化渠道消息契约](contracts/versioned-channel-message-runtime.md)
- [Loopback 控制面契约](contracts/loopback-control-plane.md)
- [本地开发运行手册](runbooks/local-development.md)
- [生产切换离线演练手册](runbooks/production-cutover-dry-run.md)
