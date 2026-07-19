# R13-C2-A 内存终态历史目录设计

- 状态：已实现并完成聚焦验证。
- Contract：[R13 C2-A 内存终态历史目录](../contracts/r13-terminal-history-catalog.md)
- Fixture：`testdata/golden/control-plane/r13-terminal-history-catalog-v1.json`。
- 数据源：仅 `ActiveTurnRegistry` 的有界、TTL 终态 Tombstone；不读取任何会话、消息或 SQLite 数据。

```text
terminal OutboundMessage / source end
        │
        ▼
ActiveTurnRegistry Tombstone
  (turnRef internal, channel, terminal state, completedAt)
        │
        ▼
actor-bound in-memory historyRef + page cursor
        │
        ▼
GET /api/v1/control/history
  (metadata-only catalog)
```

目录只投影 `historyRef`、`channel`、`terminalState`、`completedAt`。终态的内部 `turnRef` 仅用于稳定排序和
actor-bound reference 映射，绝不进入响应、日志或 query。Reference 和 cursor 都是 16-byte URL-safe 随机值、
1 分钟有效、一次性/有界的内存对象；Registry 关闭会清空 Tombstone。

本设计不实现 `GET /history/{historyRef}`。`historyRef` 在 C2-A 没有详情读取权；未来正文历史必须获得独立的数据保留、
来源绑定和脱敏 Contract，不能把 C2-A 的 Tombstone 当作 Session/Message 历史。

聚焦实现证据包括 Registry 安全终态快照、actor-bound reference/一次性 cursor、服务/Controller/Guard 的 RED/GREEN 测试，
以及 22 Case `r13-terminal-history-catalog-v1` compat Fixture。R13 阶段全门禁按既有 C3/C5 策略保留，未在 C2-A 运行。
