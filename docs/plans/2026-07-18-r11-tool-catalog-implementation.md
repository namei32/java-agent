# R11 B1 Tool Catalog 实施计划

- 状态：B1 已实现并验证；R11 后续 B 阶段未开始
- 日期：2026-07-18
- Contract：[Tool Catalog 与 Capability 治理契约](../contracts/tool-catalog-capability-governance.md)
- Spec：[R11 B1 Tool Catalog 设计](../specs/2026-07-18-r11-tool-catalog-design.md)

## 范围与暂停条件

本计划只实现 Turn-scoped 的只读目录与发现语义。不得新增真实副作用、Approval HTTP、Pending Turn、生产 Ledger、MCP 网络模式、Plugin 注入、真实 Workspace、Shell 或渠道访问。若实现需要其中任一能力，停止并先建立下一份 Contract。

## B1.1：Fixture 与 Catalog 值对象

- 新增 Java-owned Fixture，登记 Manifest。
- 先写 `ToolCatalogTest` 的 RED：元数据校验、保留名、稳定搜索、模式可见性和 Session 重置。
- 实现不可变 Entry/Catalog/Session；`List<Tool>` 旧构造保持常驻兼容。

验收：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=ToolCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## B1.2：Registry 与 Loop 纵向接线

- 先让两轮固定 Model 测试证明隐藏 Tool 在搜索前不在第一请求、同批调用不执行、第二请求出现 Schema（RED）。
- 将可见性传入 Registry/Coordinator；保留现有取消、预算、预检、审批和结果回放。
- 对 `DISABLED`、`READ_ONLY`、取消、非法调用和重复搜索写失败路径测试。

验收：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=ToolCatalogLoopTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## B1.3：Bootstrap/MCP 投影与 Golden

- 用 Catalog 注册替代 Bootstrap 仅传 `List<Tool>` 的路径；默认无 deferred 项不增加 Schema。
- 对受控 MCP Tool 验证来源投影和 deferred 可见性，绝不触发真实连接。
- 由生产 Registry/Loop 消费 Fixture，更新 Golden 规范与 Manifest。

验收：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application,agent-bootstrap -am \
  -Dtest=ToolCatalogGoldenTest,ApplicationConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## B1.4：自审、文档与提交

- 同步 Contract/Spec/Plan、Roadmap、能力矩阵、文档导航和 Golden 规范。
- 审计：生产无 Side Effect Tool、没有 Approval Endpoint/SQLite Schema、默认 `DISABLED`、无真实 MCP/Workspace/Secret 访问。
- B1 内只跑聚焦测试与格式检查；R11 所有 B 阶段结束后一次性执行 `clean verify`、`-Pfailure verify`、`-Pcompat verify`。

## R11 后续顺序

1. B2：先冻结 Approval Inbox/Pending Turn/身份与恢复 Contract；不以同步 HTTP 阻塞模拟人工审批。
2. B3：冻结 Durable Ledger/Audit SQLite Contract、迁移与恢复，然后替换生产 unavailable Port。
3. B4+：每个真实副作用 Tool 独立 Capability Contract、TDD、离线 Sandbox 验收和经授权的 Smoke。
4. 所有 B 阶段完成后更新 R11 状态并运行三套阶段门禁；在此之前 R11 不能标记完成。
