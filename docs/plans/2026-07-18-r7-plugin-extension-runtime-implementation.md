# R7 插件与扩展运行时实施计划

- 状态：G0 已冻结；等待 P1 连续 TDD
- 分支：`agent/r7-r9-runtime`
- 前置：R6.5 已通过 PR #9 合入 `main`，主分支三套 CI 全绿

## 任务

1. P1：Java-owned Fixture、Kernel Manifest/ID/Lifecycle/稳定码 Contract。
2. P2：Application Tap Dispatcher，固定顺序、独立上限、超时和失败隔离。
3. P3：受信 `ServiceLoader` 发现、重复拒绝和 Disabled 零资源。
4. P4：External stdio Bridge，hello、帧上限、协议错误、进程退出与共享关闭 Deadline。
5. P5：默认关闭 Properties/Spring 接线及 Chat/Message/Tool/Proactive Tap。
6. P6：`failure`/`compat` Fixture、架构审查和阶段门禁。

禁止：真实 Python Plugin、Shell、网络、热更新、Tool/Channel 贡献、Workspace 写入、Secret 或真实数据。
P6 后执行 Spotless、默认、`failure`、`compat`、依赖/Secret/Workspace 审计；通过后再进入 R8。
