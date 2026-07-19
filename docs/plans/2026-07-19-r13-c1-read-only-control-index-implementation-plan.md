# R13-C1 本机只读控制索引实施计划

> **执行状态：已完成（2026-07-19）**
> **授权边界：仅 C1。远程访问、真实 Telegram、CLI+Web、前端、历史正文与写操作继续冻结。**

## 目标

实现已冻结的 R13-C0 Fixture 的唯一候选 surface：认证后的
`GET /api/v1/control/index`。它只投影活动 Turn 与 Channel 健康的最小安全索引。

## TDD 顺序

1. 已扩展 Loopback guard：仅 index 可携带 `pageSize`/`cursor` query，其余 control 路径继续禁止 query，POST 仍稳定拒绝。
2. 已实现并测试 `ControlIndexResponse`、`ControlIndexCursorStore` 与 `ControlPlaneStatusService.index(...)`：安全字段、稳定排序、20/50 限制、一次性 actor-bound cursor、退化快照。
3. 已增加 MockMvc 证据：Bearer、无 actor 投影、无原始值、非法/超限/重复 query 的稳定错误、默认禁用不绑定 Controller。
4. 已最小接线；既有 `/status`、`/turns`、恢复和审批写路径的语义未改。
5. 已运行 C1 聚焦 default 测试与 compat Fixture。R13 阶段全门禁（default、`failure`、`compat`）仍留在 C3/C5，不在本小步例行运行。

## 验收

- 20 场 Fixture 的 C1 相关行为在代码测试中具备可执行证据。
- `DISABLED` 没有索引 Controller；LOOPBACK 仅可通过已有本机认证访问。
- 响应和日志不含 C0 Fixture 的原始诱饵值，也不含 actor/token/session/正文。
- 无网络、真实渠道、数据库写入或副作用。

## 完成证据

- 聚焦 default：`./mvnw -pl agent-bootstrap -am -Dtest=LoopbackRequestGuardTest,ControlIndexCursorStoreTest,ControlPlaneStatusServiceTest,ControlPlaneControllerTest,ControlPlaneConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`，17 tests 通过。
- Contract Fixture：`./mvnw -pl agent-kernel -am -Pcompat -Dtest=ReadOnlyControlIndexContractFixtureTest -Dsurefire.failIfNoSpecifiedTests=false test`，1 test 通过。
- 未运行 R13 阶段全门禁，也未启用网络、真实 Telegram、CLI+Web、前端、历史正文、SQLite 写入或控制面写 API。
