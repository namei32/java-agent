# ADR-0036：将控制面历史限制为内存终态元数据目录

- 状态：已接受
- 日期：2026-07-19
- 决策人：用户，批准 R13-C2-A
- 关联：[R13-C2-A 内存终态历史目录 Contract](../contracts/r13-terminal-history-catalog.md)

## 背景

R13-C1 已提供默认关闭的 Loopback 活动 Turn/渠道索引，但它不能回答“刚刚结束的 Turn 是否可见”这一只读运维问题。
Python Dashboard 的 Session/Message API 却携带任意 Session path、正文和更宽的数据边界；直接复用它会把历史正文、
身份和 SQLite 保留策略一并引入 Java 控制面。

用户批准了一个更窄的 C2-A：只在既有 Loopback、短期 Bearer 与 Servlet 门禁内列举本进程仍保留的终态 Tombstone。

## 决策

1. 唯一新增 surface 为 `GET /api/v1/control/history`；默认 `DISABLED` 不映射 Controller，远程、非 GET、body 与未批准
   query 继续 Fail Closed。
2. 数据源仅为 `ActiveTurnRegistry` 的有界、TTL 内存 Tombstone；不得读取 `sessions.db`、Memory、Channel Ledger、
   Workspace、Python 文件、真实渠道或网络。
3. 每项只投影服务端签发的 `historyRef`、公开 channel、终态和完成时间。内部 `turnRef` 只可用于稳定排序与 actor-bound
   映射，绝不进入响应、日志或请求参数。
4. `historyRef` 与 continuation cursor 均为 16-byte URL-safe、1 分钟、内存有界且 actor-bound 的不透明值；cursor 一次性。
   C2-A 不提供详情 Route，也不接受 `historyRef`。
5. Registry 快照异常只返回空的 `DEGRADED/CONTROL_SNAPSHOT_UNAVAILABLE` 投影；Runtime 关闭返回空的稳定关闭状态。

## 后果

实现满足当前终态可见性的最小运维需求，却不构成 Session/Message 历史、搜索、持久化保留或任何控制面写权限。未来 C2-B
若要读取正文，必须独立批准数据来源、保留期、可见 role/字段、脱敏、actor/Scope 绑定、详情错误和审计 Contract；不得把
本 ADR 的 Tombstone 或 `historyRef` 自动扩展为详情能力。
