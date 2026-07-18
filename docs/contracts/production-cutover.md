# R9 生产切换、回退与 Python 退役契约

- 阶段：R9
- 状态：已冻结；默认 `PLAN_ONLY`，不授权真实切换
- 日期：2026-07-18
- 关联 ADR：[ADR-0014：Python 退役前必须完成可回退演练](../adr/0014-require-rehearsal-before-python-retirement.md)
- 关联设计：[R9 生产切换设计](../specs/2026-07-18-production-cutover-design.md)
- 实施计划：[R9 生产切换计划](../plans/2026-07-18-r9-production-cutover-implementation.md)
- Runbook：[生产切换离线演练手册](../runbooks/production-cutover-dry-run.md)

## 1. 范围

R9 提供切换资格检查、脱敏副本演练、备份清单、差异报告、受控切换状态机和回退 Runbook。它不读取或写入
真实用户 Workspace、配置、Memory、SQLite、Token 或网络服务；也不自动停止 Python、启动 Java 或宣布
Python 退役。

## 2. 模式与状态机

`agent.cutover.mode` 严格为 `PLAN_ONLY|REHEARSAL`，默认 `PLAN_ONLY`。两种模式都禁止真实生产路径；
`REHEARSAL` 只接受显式 `--sandbox-root` 下新建的脱敏副本，并要求该路径不等于配置 Workspace、仓库根、
Home 或任何已存在生产目录。

计划状态为：`DRAFT -> ELIGIBLE -> BACKED_UP -> REHEARSED -> READY -> CUTTING_OVER -> OBSERVING ->
ROLLED_BACK|COMPLETED`。本实现只允许到 `READY`；`CUTTING_OVER`、`OBSERVING`、`COMPLETED` 需要一份
单独、带目标/窗口/负责人/回退负责人/备份位置的生产执行授权。

## 3. 资格、备份与差异

资格要求：已合入的 R7/R8、默认/`failure`/`compat` 证据、Schema 版本兼容、配置只读解析、脱敏副本、
备份可读校验、回退命令演练和无未解决高风险 Review。报告只记录路径类别、SHA-256、文件数、Schema 版本、
稳定诊断码和差异摘要，不输出正文、Token、Prompt、Memory 或真实路径。

备份计划覆盖 Python/Java 相关 SQLite 主库、WAL/SHM、配置与 Markdown Memory，但 V1 只对 sandbox 中的
显式输入执行。备份必须原子写到新的目标目录，生成 Manifest，验证可读性；不覆盖、不删除原始输入。

## 4. 回退与观察

Runbook 必须以可验证的检查点定义：停止一个写入者、恢复备份、重新启用 Python 写入者、验证 SQLite 完整性
及会话/Message/可靠投递计数。任何未知执行、差异超过阈值、失败率/延迟异常或监控缺失都触发回退；不得
自动忽略或重放 Turn。

## 5. 验收与暂停

离线 Fixture 覆盖非法根目录拒绝、Manifest、漏文件、损坏备份、Schema/版本不兼容、差异阈值、幂等
rehearsal 和 rollback 指令。R9 代码完成不等于已生产切换；真实切换、Secret、部署、网络 Smoke、写入真实
Workspace 与 Python 退役必须由用户单独批准。
