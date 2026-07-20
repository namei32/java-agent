# R14 P3–P5 安全自动化实施计划

- 状态：P3、P4、P5 已完成连续 TDD；默认、`failure`、`compat` 三套完整门禁均已通过
- 日期：2026-07-19
- 适用环境：固定 Clock、固定 ID、Fake Embedding、Fake Memory/Peer Port、临时 Java SQLite；不使用网络、真实进程、真实渠道、生产数据或用户 Workspace

## P3：逐 Mutation 的 Java-native 主动记忆

1. 新增 `proactive/r14-proactive-memory-mutation-v1` Fixture、Manifest 条目和 Fixture Consumer；先以缺失类型/错误行为得到 RED。
2. 实现私有候选的单次 claim、`CAPTURE_FIXED_LOCAL_NOTE` Proposal、独立 Pending/Approval/Anchor、AES-GCM Capsule 与安全 Outcome。
3. 实现只依赖 Fake Java-native Memory Mutation Port 的 Recovery；覆盖批准、取消、到期、认证、并发、Port/审计/提交失败及零重放。
4. 使用 Fake Store/Port 验证内部 Scope Hash、固定 `fake-r14-memory-v1` 标识和 `CREATED`/`REINFORCED` 安全 Receipt；
   不调用实际 Embedding，不注册 Adapter、Bean、SQLite 或 Memory DML。

## P4：本地 Fake Peer Process

1. 新增 `proactive/r14-local-fake-peer-process-v1` Fixture、Manifest 条目与 RED Consumer。
2. 实现严格 Manifest/Card、资源预算、Task Ref/状态、认证 Capsule、Pending/Approval/Anchor 与无泄漏 Outcome。
3. 实现注入 Fake Peer Process Port 的显式 Recovery；覆盖开始前/运行中取消、过期、并发、输出净化、失败、`UNKNOWN` 和零重放。
4. 审计源码，证明没有 ProcessBuilder、Runtime.exec、网络或文件 I/O。

## P5：受信 Deferred Catalog

1. 新增 `tools/r14-trusted-proactive-catalog-v1` Fixture、Manifest 条目与 RED Consumer。
2. 实现无生产接线的 static Toolset/Placeholder；固定两个空参数 Schema、风险、版本和搜索提示。
3. 验证默认隐藏、`tool_search` 后可见、Placeholder 零副作用、与 `forget_memory` 共存及禁用零注册。

## 阶段门禁

每个子任务保存一次有效 RED 和聚焦 GREEN。P3–P5 全部完成后，执行：

```bash
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

聚焦门禁已通过：P3 Fixture 2 项、P3 Recovery 默认 7 项 + failure 1 项；P4 Fixture 15 场景、P4 Recovery 默认 8 项 +
failure 1 项；P5 Fixture 9 场景；Golden Manifest 亦通过。随后格式化、模块边界、Secret、Workspace、默认禁用和工作树
审计均已完成。阶段末三套完整 Reactor 门禁 `./mvnw clean verify`、`./mvnw -Pfailure verify`、`./mvnw -Pcompat verify`
均已通过。任何真实 Provider/Embedding、生产 Memory DML、进程、文件、网络、真实 Peer、渠道、Bootstrap 配置或自动执行
需求都停止本计划并取得新授权。
