# 被动聊天 MVP Minor 加固实施计划

- 状态：实施中
- 日期：2026-07-13
- 来源：[被动聊天 MVP 实施计划](2026-07-13-passive-chat-mvp-implementation.md)最终审查遗留的 Minor 技术债

## 目标

在不扩大 HTTP MVP 契约、不访问真实模型和不写入真实工作区的前提下，关闭已记录的边界测试、领域不变量、事务、可观察性和构建告警技术债。

## 范围与完成标准

### Task H1：领域与应用层边界

- `ConversationHistorySelector` 使用不会发生 `int` 加法溢出的字符累计，并补多轮顺序、轮次间孤立消息、不可变返回值测试。
- `ChatResult` 拒绝空 Session、空 Assistant 和非 `ASSISTANT` 角色。
- `KeyedSessionExecutionGate` 的饱和换算和等待线程测试断言真实目标状态，移除微小误判窗口。

聚焦命令：

```bash
./mvnw -pl agent-application -am -Dtest=ConversationHistorySelectorTest,ChatResultTest,KeyedSessionExecutionGateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

生产行为变化采用一次有效 RED/GREEN；纯测试加固不人为破坏已有实现。

### Task H2：SQLite Schema 与事务边界

- 验证 Schema 初始化幂等且保留已有数据和未知列。
- 验证反向损坏的 `messages` 表被拒绝且不产生部分初始化。
- 验证主键、必填字段、默认值和 `(session_key, seq)` 唯一约束。
- 验证已有 Session 第二轮写入失败时，消息、游标和元数据全部回滚。
- 验证从已有最大消息序号恢复后，新轮次 ID 和 `next_seq` 连续。

聚焦命令：

```bash
./mvnw -pl adapter-sqlite -am -Dtest=SqliteSchemaInitializerTest,JdbcSessionRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task H3：可观察性、健康检查与测试告警

- 补聊天、模型和数据库成功路径的结构化字段与脱敏断言。
- 补 SQLite Health `DOWN` 分支。
- 清理可由项目测试代码消除的 Mockito 动态 Agent 和 Deprecated API 告警；第三方告警若无法消除，记录来源和后续升级条件。

聚焦命令：

```bash
./mvnw -pl adapter-spring-ai,agent-bootstrap -am test
```

## 集中门禁

全部任务完成后只集中运行一次：

```bash
./mvnw spotless:apply
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

随后检查：

- `agent-kernel` 依赖树不含 Spring、Spring AI、JDBC、Reactor 或 Provider SDK。
- Git 跟踪内容不含 API Key、真实数据库、用户 Workspace 或记忆正文。
- 默认与兼容 Profile 不访问真实模型。
- Git diff 只包含本轮文档和加固变更。

## 实施结果

完成后在此记录准确命令、测试数、告警和任何保留项，并同步更新原 MVP Plan、能力矩阵和 Roadmap。没有验证证据前不得把状态改为“已实现并验证”。
