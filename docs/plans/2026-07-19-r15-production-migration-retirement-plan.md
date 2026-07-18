# R15 生产迁移与 Python 退役计划

- 状态：差距审计和计划已冻结；**未开始真实迁移**
- 日期：2026-07-19
- Python 证据基线：`akashic-agent` 提交 `b65a5430e332c8733b981dfc2dfbc3eb1967e9ef`
- Java 证据基线：`agent/r12-skill-catalog`，含 R9 sandbox-only `cutover-plan`/`cutover-rehearse`/`cutover-verify`
- 前置：[R9 生产切换契约](../contracts/production-cutover.md)、[R9 离线演练手册](../runbooks/production-cutover-dry-run.md)、[完成度审计](../architecture/2026-07-19-akashic-java-completion-audit.md)

## 1. 本轮结论

R9 证明的是“离线 sandbox 演练可回退”，不是生产部署能力。它只能对新建 sandbox 内的脱敏样本生成备份、差异与
`READY` 报告；不能进入 `CUTTING_OVER`，不读取真实 Workspace/Token/数据库，不启动 Java 或停止 Python。

Python 仍拥有 Docker 调试沙盒、渠道/Provider/Memory/Plugin 的运行配置、IPC/Telegram/QQ/Plugin 集成和实际
运行探针。Java 当前没有等价的容器部署资产、真实渠道/前端/自动化运行能力，也没有获批的数据迁移。因此不能宣布
Python 退役，更不能把 R9、CI 或本计划解释为生产切换授权。

## 2. 差距与迁移原则

| 领域 | Python 已有表面 | Java 当前状态 | R15 要求 |
| --- | --- | --- | --- |
| 可复现实例 | Docker 调试镜像、Compose、隔离 Profile/Home/Workspace、运行探针 | Maven/JAR 本地启动与配置检查；无 Java 容器/Compose 生产资产 | M1 用不可含 Secret 的不可变构建、SBOM/依赖扫描、最小运行用户、只读根文件系统和独立可写卷建立 Java sandbox；不复用 Python Docker 配置 |
| 配置与密钥 | TOML、环境变量、渠道/Provider/Plugin/MCP 配置 | 环境变量和只读 Python TOML 兼容解析；敏感 Mode 默认关闭 | M2 冻结逐字段映射、未支持字段、Secret 来源/轮换、启动前 Config Check 和回退配置；不把 Python 动态配置/本地文件加载隐式带入 Java |
| 数据与状态 | Python Session、Markdown、`memory2`、Proactive、渠道/Plugin 状态 | Java Session/Memory/Channel/Proactive SQLite 独立；旧 `memory2` 已明确不迁移 | M3 仅迁移获批且已语义对齐的数据集；每个库有一致性快照、WAL/SHM、校验、计数和恢复。未对齐数据保持 Python 只读或不迁移，不能伪造转换 |
| 灰度与观察 | Python 调试探针及运行日志 | R9 sandbox Manifest/差异；无生产观测闭环 | M4 先影子只读或隔离测试流量，再限范围灰度；固定成功/失败阈值、指标、告警、观察期、停止条件与人工回退负责人 |
| Python 停止与退役 | 现有运行环境 | 无退役操作 | M5 仅在所有功能对齐、真实验收、备份恢复演练和观察期通过后，按书面变更单停止单一写入者；保留可启动的 Python 回退版本和数据恢复窗口 |

## 3. 不可跨越的边界

1. 不自动复制、读取或删除真实 Python Workspace、`memory2.db`、Token、聊天内容、附件、日志或渠道凭据。
2. 一次只允许一个生产写入者；禁止无计划的双写、自动重放或“先切再补数据”。对未确认状态必须停机并回退，而不是猜测。
3. 任何真实 Provider、Telegram/QQ/IPC/Plugin/MCP、网络端口、容器 Registry、云服务或生产数据库操作需要单独的操作授权和变更窗口。
4. 备份必须包含 SQLite 主库、WAL/SHM 和版本化 Manifest，且在独立位置实际恢复验证；普通文件复制不能代替运行中的 SQLite 备份。
5. 只有完成当前功能差距（尤其 R11–R14 中仍未实现的副作用、渠道、Dashboard、自动化与 Peer）并留下对应真实验收证据，才可开始 M4/M5。

## 4. 解冻后的连续验收顺序

### M0：生产执行 Contract 与变更单（RED）

冻结目标环境、版本、维护窗口、负责人、回退负责人、可写系统清单、指标、阈值、停止条件和授权编号。缺少任一字段
即不得创建生产命令。R9 的状态机继续只作为前置证据，不能扩大其执行权限。

### M1：隔离 Java 运行实例（GREEN）

创建不含真实 Secret 的 Java sandbox 部署描述和构建证明；验证默认 Disabled、Loopback/网络边界、只读文件系统、
独立空 Workspace、健康检查、关闭和日志脱敏。先用 Fake Provider/Channel，不连真实基础设施。

### M2：配置和 Secret 演练（GREEN）

对每个获支持字段建立版本化配置 Fixture：来源优先级、未知字段、Mode、Secret 引用、密钥轮换、失败投影和回退。
真实 Secret 仅由批准的部署系统注入，测试只使用占位值或 Fake Secret Provider。

### M3：数据副本与恢复演练（GREEN）

以脱敏副本验证每类获批准的数据：一致性快照、Schema 版本、导入/拒绝规则、SHA-256/计数、备份 Manifest、恢复和
回退。旧 Python `memory2`、未对齐 Tool/Channel 状态或未知表默认拒绝迁移。通过后仍只允许 sandbox。

### M4：受限灰度与观察（GREEN）

先验证影子只读或人工生成流量，再在明确的小范围、单写入者环境执行。观测 Chat/Tool/Channel/Memory/Proactive
成功率、延迟、SQLite 完整性、Outbox/Receipt、错误码和资源预算；任一阈值异常即按 M0 回退。不得自动扩大流量。

### M5：Python 退役（人工执行）

只有所有对齐项目、M1–M4、生产恢复演练及观察期均通过，且用户书面批准后，才停止 Python。保留已验证备份、可运行
Python 版本、恢复手册和约定的回退窗口；窗口结束前不得删除 Python 数据或基础设施。

## 5. 验收与当前状态

- R9 的 `CutoverCommandTest` 与 `SandboxCutoverAdapterTest` 是 M0–M3 的离线基础证据，不证明生产迁移。
- 每个 M 阶段都要先写版本化 Fixture、RED/GREEN 测试和运行手册；涉及副作用/渠道/网络的阶段还要先通过相应 R11–R14 Contract。
- 当前只允许 M0 的文档准备和 R9 sandbox 演练；M1–M5 均等待功能差距关闭与明确操作授权。
