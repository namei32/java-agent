# Tool Runtime 安全加固实施计划

- 状态：已完成
- 当前执行状态：Task S1 至 S9 已完成
- 日期：2026-07-14
- Spec：[Tool Runtime 安全加固设计](../specs/2026-07-14-tool-runtime-safety-design.md)
- Contract：[Tool Runtime 安全契约](../contracts/tool-runtime-safety.md)

## Task S1：治理、模式与配置（已完成）

- 建立 Spec、Plan 和功能分支。
- 增加 `ToolRuntimeMode`、`ToolRuntimeSettings` 和 Bootstrap 配置绑定。
- `DISABLED` 模式不注册工具、不发送 Tool Definition。

聚焦验收：配置与 Application 模式测试一次 RED、一次 GREEN。

实施结果：新增 `ToolRuntimeMode/Settings` 和完整 Bootstrap 默认预算；`DISABLED` 模式不注册或发送 Tool Definition，并拒绝 Provider 意外返回的 Tool Call。有效 RED 为协议类型缺失，GREEN 实际执行 Application 3 Tests、Bootstrap 6 Tests，全部通过。

## Task S2：批次预算、Schema 与 Result 边界（已完成）

- 实现整批调用预算预检。
- 实现注册期 Schema 编译检查和运行期参数校验。
- 实现 Result 字符上限。
- 增加稳定预算异常和 HTTP 映射。

聚焦验收：Application Runtime 安全测试一次 RED、一次 GREEN。

实施结果：增加单响应和单轮累计预算的整批预检，注册期拒绝不支持的 Schema，运行期把非法参数转换为固定安全 `ERROR`，Result 按 Unicode 码点执行上限检查；预算失败稳定映射为 `TOOL_CALL_LIMIT_EXCEEDED` 和 HTTP `502`。有效 RED 为预算异常类型缺失，GREEN 实际执行 Application 5 Tests、Bootstrap 7 Tests，全部通过。

## Task S3：Arguments Adapter 边界（已完成）

- 在 JSON 解析前执行 UTF-8 字节上限。
- 保持 Provider 异常分类和 Schema Carrier 边界。

聚焦验收：Spring AI Adapter 测试一次 RED、一次 GREEN。

实施结果：Spring AI Adapter 在反序列化前按 UTF-8 检查 Provider 原始 Arguments，边界值允许、超限统一转换为不含原文的 `InvalidModelResponseException`；Bootstrap 配置通过 `agent.tools.max-argument-bytes` 注入。有效 RED 为新构造边界缺失，GREEN 实际执行 Adapter 9 Tests，全部通过。

## Task S4：超时、并发许可与取消（已完成）

- 实现公平 Semaphore、Virtual Thread FutureTask 和共享 Deadline。
- 实现 `TurnCancellation`/Source、活动工具中断和提交前检查。
- 隔离取消回调异常，不使用不稳定 Sleep 测试。

聚焦验收：超时/并发/取消测试一次 RED、一次 GREEN。

实施结果：增加项目自有 `TurnCancellation`/Source 和稳定取消异常；Runtime 使用公平 Semaphore、Virtual Thread 与 FutureTask，使许可等待和执行共享 Deadline，并确保许可只在实际任务退出时释放。模型前后、每个工具前和提交前均检查取消，活动工具产生 `CANCELLED` 后终止且不提交。有效 RED 为取消协议缺失；首轮 GREEN 发现测试夹具会及时响应中断，经改为模拟不响应中断的缺陷工具后，验证了许可不会提前归还；最终 Application 5 Tests 全部通过。

## Task S5：Tool Golden 与装配（已完成）

- 扩展 Migration Contract Fixture 和 Manifest。
- 让 Compat 测试逐案执行新增场景。
- 完成 Bootstrap 装配、环境变量示例和运行手册。

聚焦验收：Golden 生成确定且 Compat 目标测试通过。

实施结果：新增 `tools/runtime-safety.json` 和 Manifest Hash，9 个场景覆盖单响应/单轮预算、Arguments 字节、Schema、Result、执行超时、许可等待、活动取消和 `DISABLED` 模式；Application 与 Spring AI Adapter 均回放生产代码。使用相邻 Python 仓库 `.venv` 连续生成两次，夹具与 Manifest Hash 均保持一致；有效 RED 为夹具缺失，GREEN 实际执行 Application 1 Test、Adapter 10 Tests，全部通过。环境变量模板、README、运行手册、Roadmap 和能力矩阵已同步。

## Task S6：初始阶段门禁与提交（已完成）

- 更新 Contract 实施状态、Roadmap、能力矩阵和 README。
- 执行格式、默认、Failure、Compat、架构、Secret 和 Workspace 门禁。
- 自审并提交短生命周期功能分支。

真实 Provider Smoke 不属于自动门禁；实现完成后需单独授权执行。

实施结果：最终自审修正了 Golden `pythonEvidence` 登记、并发许可夹具的竞态断言、含 `null` 的 Enum 安全比较、累计调用数整数溢出和许可后 Deadline 复检。最后一次完整门禁结果如下：

- `./mvnw --batch-mode --no-transfer-progress spotless:check`：六模块通过。
- `./mvnw --batch-mode --no-transfer-progress clean verify`：Kernel 18、Application 33、SQLite 13、Spring AI 15、Bootstrap 37 Tests，全部通过。
- `./mvnw --batch-mode --no-transfer-progress -Pfailure verify`：Application 9、SQLite 13 Tests，全部通过。
- `./mvnw --batch-mode --no-transfer-progress -Pcompat verify`：Kernel 24、Application 38、SQLite 15、Spring AI 16、Bootstrap 48 Tests，全部通过。
- Kernel 依赖树仅含测试依赖，没有 Spring/JDBC/Provider SDK；禁止依赖扫描、Secret 扫描和 Workspace 产物扫描均为零命中。

该阶段真实 Provider Smoke 尚未执行。依据安全契约，部署继续保持 `AGENT_TOOL_MODE=DISABLED`。

## Task S7：Spring AI Provider Options 发布阻塞修复（已完成）

- 为 `OpenAiCompatibleAdapterIT` 增加真实本地 HTTP `tool_calls` 往返测试。
- 首个请求断言保留模型配置并包含 Tool Schema。
- 第二个请求断言包含 Assistant Tool Call 和 Tool Result。
- 从 `chatModel.getOptions()` 取得实际 Provider Options，通过 `ToolCallingChatOptions.mutate()` 注入 Callback，保留 `OpenAiChatOptions` 运行时类型。

有效 RED：`OpenAiChatModel` 把新建的 `DefaultToolCallingChatOptions` 强制转换为 `OpenAiChatOptions`，产生 `ClassCastException`，真实 HTTP 工具路径失败。GREEN：同一聚焦命令执行 Adapter 9 个单测和 `OpenAiCompatibleAdapterIT` 7 个集成测试，全部通过。第一次使用错误的 Maven 选择器没有执行目标测试，不计入 RED 证据。

## Task S8：任务启动前取消的许可泄漏修复（已完成）

- 引入包内可控 `ToolTaskStarter` 测试接缝，不改变生产默认 Virtual Thread 行为。
- 增加“提交后、正文启动前取消”的确定性测试，证明后续调用不会因许可泄漏超时。
- 增加线程启动失败测试，要求同步释放许可并返回固定安全错误。
- 把许可释放移动到 Virtual Thread 外层 Worker 的无条件 `finally`，不再依赖 `FutureTask` 的 Callable 是否实际执行。

有效 RED：启动前取消后第二个调用得到 `TIMEOUT`，线程启动失败向调用方泄漏 `IllegalStateException`。GREEN：`ToolRuntimeConcurrencyCancellationTest` 7 个测试全部通过；安全与并发测试均标记为 `failure`，避免 Failure Profile 漏跑。

## Task S9：补充门禁与真实 Provider Tool Smoke（已完成）

发布阻塞修复后的最终本地门禁：

- `./mvnw --batch-mode --no-transfer-progress clean verify`：Kernel 18、Application 35、SQLite 13、Spring AI 16、Bootstrap 37，共 119 Tests，全部通过。
- `./mvnw --batch-mode --no-transfer-progress -Pfailure verify`：Application 21、SQLite 13、Spring AI 1，共 35 Tests，全部通过。
- `./mvnw --batch-mode --no-transfer-progress -Pcompat verify`：Kernel 24、Application 40、SQLite 15、Spring AI 17、Bootstrap 48，共 144 Tests，全部通过。
- 首次完整门禁在 Spotless 检出两处纯格式差异后停止；应用仓库格式规则并从头重跑，最终通过，不计为功能 RED。

获得真实网络与可能费用授权后，使用临时 Workspace 对 DeepSeek `deepseek-v4-flash` 执行 `real-model-smoke`：2 个真实集成测试通过。证据覆盖普通回答、`current_time` Tool Call、Java 执行、Tool Result 回送、最终文本、两次模型请求、成功生命周期事件以及 SQLite 仅提交最终 User/Assistant 两条消息。测试未记录 API Key、Provider 原始错误正文、完整 Prompt、Arguments 或 Result。

该 Smoke 只形成当前 Provider/模型组合的能力证据，不自动授权部署切换。项目本地 `.env`、`.env.example` 和运行手册示例均继续保持 `AGENT_TOOL_MODE=DISABLED`；副作用、审批、幂等和沙箱 Contract 仍是下一独立里程碑，在此之前不迁移文件写入、Shell、Web 写入或消息发送工具。
