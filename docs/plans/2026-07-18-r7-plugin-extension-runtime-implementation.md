# R7 插件与扩展运行时实施计划

- 状态：G0、P1、P2、P3、P4、P5、P6 已完成
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

P1 证据（2026-07-18）：先添加 20 Case Java-owned Plugin Fixture 与 `PluginContractTest`；目标 Maven
测试编译因缺少 Manifest、ID、Catalog、稳定码和违例类型失败，构成有效 RED。随后以纯 Kernel 值对象实现
严格 ID、Manifest、能力 allowlist、重复 Catalog ID、稳定重试码与脱敏错误，同一目标命令 2/2 GREEN；
`GoldenManifestTest` 在 `compat` Profile 复核 Fixture Hash 通过。

P2 证据（2026-07-18）：先添加 `PluginTapDispatcherTest`，目标 Maven 测试因缺少不可变 Tap Event、
Tap SPI、调度器与隔离稳定码而编译失败，构成有效 RED。随后实现无框架、可注入执行器和 Deadline 的
Dispatcher；同一目标命令 3/3 GREEN，覆盖 `(priority desc, pluginId asc)` 顺序、发布线程不直接调用、
单 Plugin timeout 禁用、Runtime Failure 隔离以及审计不含原始 Plugin ID 或异常正文。命令：
`./mvnw --batch-mode --no-transfer-progress -pl agent-application -am -Dtest=PluginTapDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false test`。

P3 证据（2026-07-18）：先添加 `JavaServicePluginDiscoveryTest`，目标 Maven 测试因缺少 `AgentPlugin`
SPI、严格 Plugin Mode 和受信发现器而编译失败，构成有效 RED。随后以 `ServiceLoader` 的惰性 Supplier
实现 classpath 发现；仅在 `JAVA_SERVICE` 且显式 allowlist 非空时读取 Provider，先固定 Manifest/Tap
投影再校验重复和来源。相同目标命令 3/3 GREEN，证明 Disabled/空 allowlist 不实例化 Provider、结果遵循
配置 ID 顺序、重复 ID 与错误 `EXTERNAL_STDIO` 来源均在激活前稳定拒绝。命令：
`./mvnw --batch-mode --no-transfer-progress -pl agent-bootstrap -am -Dtest=JavaServicePluginDiscoveryTest -Dsurefire.failIfNoSpecifiedTests=false test`。

P4 证据（2026-07-18）：先添加 `ExternalStdioPluginBridgeTest`，目标 Maven 测试因缺少 Bridge、受限
Transport、request ID、帧预算和稳定异常而编译失败，构成有效 RED；随后加入协议实现与 `PluginTapException`
的稳定码透传。协议目标命令 4/4 GREEN，覆盖 hello/Manifest 交叉校验、安全 Tap 投影、错误关联、帧超限、
进程 I/O 与共享关闭 Deadline。另以 `ExternalStdioCommandTest` 先 RED 后 GREEN，固定绝对非 Shell 可执行文件
和无控制字符 token；JDK Transport 清空继承环境、固定 `/` 工作目录、限制读取帧并在超时/关闭时终止进程。
命令：`./mvnw --batch-mode --no-transfer-progress -pl agent-bootstrap -am -Dtest=ExternalStdioPluginBridgeTest,ExternalStdioCommandTest -Dsurefire.failIfNoSpecifiedTests=false test`。

P5 证据（2026-07-18）：先添加 `PluginPropertiesTest` 与 `PluginRuntimeTest`，前者因缺少严格
`agent.plugins` 配置失败，后者因缺少 Bootstrap Runtime 失败，构成 RED。随后接入默认 `DISABLED` 的
Properties、classpath/stdio Runtime、Virtual Thread Tap 执行器、共享 Deadline 以及 Spring 的
`TurnLifecycleObserver` Bean；Disabled 不发现 Provider、不启动进程、不创建 dispatcher，启用时强制全局
Tool Runtime 为 `READ_ONLY`。聚焦 GREEN 共 12 个 Bootstrap 场景，另以 `MessageTurnServiceTest` 证明
终端 Message 仅在权威 Sink 发布后观察；Chat/Tool/Message 映射均只发送域隔离 Hash，R8 可通过
`publishProactive` 复用 `PROACTIVE_TAP`。命令：
`./mvnw --batch-mode --no-transfer-progress -pl agent-bootstrap -am -Dtest=PluginPropertiesTest,PluginRuntimeTest,ApplicationConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`，
以及 `./mvnw --batch-mode --no-transfer-progress -pl agent-application -am -Dtest=MessageTurnServiceTest,PluginTapDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false test`。

P6 证据（2026-07-18）：审查发现 External stdio Bridge 在 Tap 协议错误后仍会保持可用；先加
`protocolFailureDuringTapClosesOnlyThatBridgeAndDoesNotAllowReplay`，确认 Transport 未关闭的 RED，随后改为
关闭该 Bridge 并拒绝重放，同一目标 5/5 GREEN。格式化后执行阶段全门禁：
`./mvnw --batch-mode --no-transfer-progress clean verify`、
`./mvnw --batch-mode --no-transfer-progress -Pfailure verify`、
`./mvnw --batch-mode --no-transfer-progress -Pcompat verify`；三套均完成，Surefire/Failsafe 报告无
`failure`/`error`。本阶段未启动真实 Provider、真实 Python Plugin、远程访问、CLI+Web 或前端变更。
