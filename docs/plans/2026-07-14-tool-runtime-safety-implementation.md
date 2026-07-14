# Tool Runtime 安全加固实施计划

- 状态：实施中
- 当前执行状态：Task S1、S2 已完成，Task S3 进行中
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

## Task S3：Arguments Adapter 边界

- 在 JSON 解析前执行 UTF-8 字节上限。
- 保持 Provider 异常分类和 Schema Carrier 边界。

聚焦验收：Spring AI Adapter 测试一次 RED、一次 GREEN。

## Task S4：超时、并发许可与取消

- 实现公平 Semaphore、Virtual Thread FutureTask 和共享 Deadline。
- 实现 `TurnCancellation`/Source、活动工具中断和提交前检查。
- 隔离取消回调异常，不使用不稳定 Sleep 测试。

聚焦验收：超时/并发/取消测试一次 RED、一次 GREEN。

## Task S5：Tool Golden 与装配

- 扩展 Migration Contract Fixture 和 Manifest。
- 让 Compat 测试逐案执行新增场景。
- 完成 Bootstrap 装配、环境变量示例和运行手册。

聚焦验收：Golden 生成确定且 Compat 目标测试通过。

## Task S6：阶段门禁与提交

- 更新 Contract 实施状态、Roadmap、能力矩阵和 README。
- 执行格式、默认、Failure、Compat、架构、Secret 和 Workspace 门禁。
- 自审并提交短生命周期功能分支。

真实 Provider Smoke 不属于自动门禁；实现完成后需单独授权执行。
