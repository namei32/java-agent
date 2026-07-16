# 渠道账本备份、恢复与回退手册

- 状态：草案，待 R6.4 Contract 批准与临时数据库演练
- 日期：2026-07-16
- 适用数据库：`<workspace>/channels/channel-ledger.db`
- Contract：[渠道可靠投递、幂等与恢复契约](../contracts/channel-reliable-delivery.md)
- ADR：[ADR-0010：使用独立 Java SQLite 渠道账本与事务 Outbox](../adr/0010-use-dedicated-sqlite-channel-ledger.md)

> 当前文档不能授权操作真实 Workspace。R6.4 实施与 CI 只在 JUnit 临时目录演练。真实数据库的备份、恢复、删除、迁移或部署必须另行批准。

## 1. 适用场景

本手册覆盖：

- R6.4 V0 到 V1 自动迁移前的 SQLite Online Backup。
- 在临时副本上验证 Backup 可读、Schema 和行数。
- Schema Migration 失败后的离线恢复。
- 关闭可靠性层并退回 R6.3 Volatile 行为。

不覆盖：

- 直接修改 `UNKNOWN`、Turn Claim、Delivery 或 Cursor。
- 在线替换数据库。
- 多实例恢复、跨机器复制、远程备份或灾难恢复服务。
- 恢复 `sessions.db`、语义记忆库或 Python 数据。

## 2. 固定安全规则

1. 任何人工文件操作前，设置 `AGENT_TELEGRAM_ENABLED=false` 和 `AGENT_CHANNEL_RELIABILITY_MODE=DISABLED`，停止应用并确认无 Java Writer。
2. 不在运行中的 WAL 数据库上使用普通 `cp` 生成“备份”；在线备份必须使用 SQLite Backup API/`.backup`。
3. 不只复制主 `.db` 而遗漏活动 WAL；停机后先检查，原数据库、`-wal`、`-shm` 作为一个隔离集合保留。
4. 任何 Backup 在替换前必须单独打开并通过 `PRAGMA quick_check(1)`。
5. 不在旧二进制中打开更高 Schema；不执行手写 `DROP/UPDATE/DELETE` 降级。
6. 恢复期间不访问 Telegram；所有检查只读、离线。
7. 命令历史、工单和日志不得包含 Workspace 真路径之外的用户正文、目标 ID、Token 或查询结果明细。

## 3. 自动迁移前备份

生产 `ChannelLedgerSchemaInitializer` 的批准行为应为：

1. 打开源数据库并执行 `quick_check(1)`。
2. 严格验证 V0 Header。
3. 在 Schema 写事务开始前调用 SQLite Online Backup。
4. Backup 文件名使用 `channel-ledger.db.v0-to-v1-<opaque>.bak`。
5. 重新打开 Backup，执行 `quick_check(1)`、版本和预期对象检查。
6. 只有全部通过才开始 V1 Migration。
7. Backup 失败时删除不完整文件并保持源数据库零 Schema 变化。

自动备份不得输出绝对路径、SQLite 异常正文或行内容；只允许稳定 `CHANNEL_LEDGER_BACKUP_FAILED`。

## 4. 临时副本演练

R6.4 自动化使用 JUnit `@TempDir` 创建虚构 V0 数据库，并证明：

- Backup 在 Migration 第一条写入前存在。
- Backup `quick_check` 为 `ok`，Header 仍是 V0。
- 源数据库迁移为 V1 且数据/约束符合 Fixture。
- 把 Backup 恢复到另一临时路径后仍是 V0。
- 再次执行相同恢复不会修改 Backup。
- 所有 Connection 关闭，测试结束无活跃 WAL/SHM/Thread。

验收测试类：

- `ChannelLedgerBackupTest`
- `ChannelLedgerSchemaInitializerTest`
- `ChannelLedgerRollbackIT`

## 5. 真实环境只读检查模板

以下模板只在另行批准真实 Workspace 后使用。先由操作者显式设置路径，不把路径提交 Git：

```bash
export CHANNEL_LEDGER_DB="<approved-workspace>/channels/channel-ledger.db"
test -f "$CHANNEL_LEDGER_DB"
sqlite3 -readonly "$CHANNEL_LEDGER_DB" 'PRAGMA quick_check(1);'
sqlite3 -readonly "$CHANNEL_LEDGER_DB" \
  'SELECT version FROM channel_schema WHERE singleton = 1;'
```

预期：

- `quick_check(1)` 只返回一行 `ok`。
- Schema 查询只返回一个受支持版本。

若输出不同，停止；不要尝试 `VACUUM`、`REINDEX`、删除 WAL 或手工改版本。

## 6. 迁移失败后的回退

### 6.1 首选：关闭可靠性层

如果普通 HTTP/CLI 必须先恢复：

1. 停止应用。
2. 设置 Telegram 和 Reliability 为 Disabled。
3. 重启并确认没有打开渠道账本、没有 Telegram Worker/网络。
4. 保留数据库及 Backup 原样，等待离线分析。

这条路径不需要立即替换文件，也是风险最低的功能回退。

### 6.2 恢复迁移前 Backup

只有在明确需要继续使用旧二进制/旧 Schema 且已批准文件写入时：

1. 停止应用并确认无 Writer。
2. 只读验证源数据库和候选 Backup。
3. 为当前数据库集合创建新的离线隔离副本。
4. 把候选 Backup 复制到同目录临时恢复文件。
5. 对临时恢复文件执行 `quick_check` 和版本检查。
6. 使用同文件系统原子 Rename 把现有 `.db/-wal/-shm` 移入带时间戳隔离目录，再把临时恢复文件原子改名为 `channel-ledger.db`。
7. 使用目标旧二进制只读/Disabled 启动检查；未批准前不重新启用 Telegram。

不得覆盖唯一的失败现场或唯一 Backup。原始集合至少保留到恢复验收结束。

## 7. 恢复后验证

恢复完成后依次验证：

- `quick_check(1)=ok`。
- Header 只有一行且版本符合目标二进制。
- 预期表/索引/View/Trigger 精确匹配该版本。
- 不存在孤立 Part/Attempt。
- Reliability Disabled 时应用不打开 DB、不启动 Delivery Worker。
- 若未来获批重新启用，首次 Poll 前完成 Recovery，并从该渠道实例的持久 offset 开始。

不得用真实消息发送作为第一项恢复验证。真实 Smoke 仍需要独立 Token、网络、测试 Chat 和数据授权。

## 8. UNKNOWN 与人工处理

V1 没有人工 Reconcile 写接口。发现以下状态时：

- `EXECUTION_UNKNOWN`
- Delivery/Part `UNKNOWN`
- In-flight Attempt 在崩溃后转 Unknown

只允许：

- 保持 Telegram 关闭或让未受影响 Delivery 继续。
- 读取聚合计数和稳定错误码。
- 备份数据库供经批准的离线分析。

禁止：

- 直接把 Unknown 改回 Pending/Running/Delivered。
- 重新生成 Delivery ID 或 Turn ID。
- 重发原 Payload 或重跑 Agent 来“试试看”。
- 仅凭用户没看到消息就断言远端未发送。

人工核查与裁决 API 属于后续独立 Contract。

## 9. 停止条件

遇到以下任一情况立即停止恢复：

- 应用仍持有数据库、WAL 或 SHM。
- Backup `quick_check` 失败、版本未知或对象不匹配。
- 找不到迁移前 Backup，或 Backup 是普通运行中主文件复制。
- 需要修改 `sessions.db`、Memory DB、真实用户消息或 Token。
- 需要手工决定 Unknown 的成功/失败。
- 需要连接 Telegram 或使用真实用户会话验证。
