# R9 生产切换与回退设计

## 1. 组件

Kernel 提供 `CutoverPlan`、`CutoverEligibility`、`BackupManifest`、`DifferenceReport` 与稳定诊断；
Application 编排资格/演练，不直接操作真实根目录；Bootstrap 提供显式 CLI `cutover-plan`、
`cutover-rehearse`、`cutover-verify`，均要求 sandbox。SQLite/Workspace Adapter 执行受限文件操作并拒绝
越界符号链接与非空输出目录。

## 2. Sandbox 约束

输入必须先规范化，拒绝 Home、仓库、配置 Workspace、父目录别名、符号链接逃逸和不可读目录。演练复制只处理
Contract allowlist 的 SQLite/Markdown/配置类别；每步写入 Manifest 并可在同一 sandbox 验证，不能删除源。

## 3. 差异与回退

比较使用 Schema、记录计数、公开字段 Hash、Fixture 结果和明确批准的例外；不比较自由模型文本或脱敏外的正文。
Runbook 只生成命令计划和检查点，生产操作必须由负责人手动确认。任何失败得到稳定失败报告并留下输入不变。

## 4. TDD 任务

C1 Kernel Fixture/状态机；C2 Sandbox Guard/Manifest；C3 Backup/Verify；C4 Difference Report；C5 CLI 与
Runbook；C6 故障/兼容/阶段验收。真实环境不在自动化中出现。
