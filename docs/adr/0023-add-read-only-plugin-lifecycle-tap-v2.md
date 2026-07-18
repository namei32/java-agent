# ADR-0023：以 API v2 增加只读 Plugin Lifecycle Tap

- 状态：已接受
- 日期：2026-07-19
- 决策范围：R12-S3

## 背景

R7 已有受信 Java SPI 与隔离 stdio Plugin Tap，但只给出粗粒度 Capability 和 Hash。Python Plugin 可以按照具体
生命周期 Phase 注册处理器；直接把新的字段塞入 API v1 的 `turn.tap`/`tool.tap` 请求会使严格外部 Plugin 无法安全
升级或区分协议。

## 决策

保留 API v1 及其 Wire 字节语义。新增 API v2 `LIFECYCLE_TAP` Capability 和 `lifecycle.tap` 方法，发送严格、
无业务正文的 Phase 投影。Java Lifecycle Event 到 Python Phase 的映射是显式且不完整的：没有对应 Java 事件时不
伪造 Prompt/Step/Approval/Side Effect Phase。

## 后果

新 Plugin 必须显式选择 API v2，旧 Plugin 保持可用。此决策只增强观察性，绝不成为 Gate、Tool 注入、配置/目录
发现、Python 运行、网络或副作用权限的隐式授权。若未来需要可变 Hook，必须另建 Capability、审批和执行契约。
