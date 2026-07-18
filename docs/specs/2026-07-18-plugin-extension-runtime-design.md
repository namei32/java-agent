# R7 插件与扩展运行时设计

## 1. 分层

`agent-kernel` 定义 Manifest、ID、不可变 Tap Event、Lifecycle、稳定码和 Java `AgentPlugin` SPI；
`agent-application` 维护有界 Lifecycle/Dispatcher，不持有 Spring 或进程类型；`agent-bootstrap` 负责
`ServiceLoader`、配置和可选 stdio 子进程。Kernel 不依赖 Python、Servlet、SQLite 或 `ProcessBuilder`。

## 2. 安全执行模型

Dispatcher 按 `(priority desc, pluginId asc)` 固定顺序复制候选，再在 Agent 锁外调用 Tap。每个 Plugin 有一个
串行执行槽与 timeout；一个 Plugin 正在处理时，新事件稳定丢弃并计数，而不能积压无界队列。Runtime Failure、
超时或协议异常将该 Plugin 标为 Failed，其他 Plugin 和主链路继续。

关闭先拒绝新 Tap，再通知 Bridge、在一个共享 Deadline 等待，最后释放 Service/进程资源。启动失败不留下
进程或执行槽；Disabled 不构造 Runtime。

## 3. Bridge 适配器

Bootstrap 的 Bridge Factory 接收经过解析的命令 token 列表，不调用 Shell。每个 Bridge 使用受控 Virtual Thread
读 stdout，写入/请求均使用项目拥有的有界队列。hello Manifest 与配置交叉校验；只要任一边不匹配就停止进程。

## 4. 与现有运行时的接线

Application 在 Chat/Message/Tool 结果已经由现有权威边界接受后才发出 Tap。Plugin 永不位于 Primary Sink、
Session Commit、Approval 决策或 Channel Delivery 路径之前。R8 的主动 Runtime 只复用 `PROACTIVE_TAP`，
不反向依赖 Bootstrap Plugin 进程。

## 5. TDD 任务

P1 Kernel Manifest/ID/Code Fixture；P2 Dispatcher 顺序/隔离/timeout；P3 Java ServiceLoader；P4 stdio
Bridge protocol/cancel/close；P5 默认关闭 Spring 接线；P6 兼容与故障矩阵。每个行为任务先 RED 后 GREEN。
