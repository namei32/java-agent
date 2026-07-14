# 只读 Context/Memory 实施计划

- 状态：实施中
- 批准日期：2026-07-14
- 日期：2026-07-14
- 阶段：R4.1
- 当前执行状态：Task C0 至 C5 已完成；从 Task C6 开始实施
- Contract：[只读上下文与记忆兼容契约](../contracts/read-only-context-memory.md)
- Spec：[只读 Context/Memory 纵向切片设计](../specs/2026-07-14-read-only-context-memory-design.md)

> 批准前只允许修改文档，不编写生产代码。批准范围不包含真实 Workspace、Memory 写入、`memory2`、Embedding、记忆 Tool、后台维护或真实模型调用。

## Task C0：Python 能力分析与范围冻结

状态：已完成。

证据：读取 Python Prompt、Context、Markdown Memory、Retrieval 协议和相关测试；执行：

```bash
.venv/bin/python -m pytest \
  tests/test_agent_core_p3_context_store.py \
  tests/test_agent_core_p4_prompt_block.py -q
```

结果：10 个测试全部通过。已形成 R4 能力矩阵，并把完整 `memory2`、写入和 Maintenance 排除在 R4.1 之外。

## Task C1：Contract 评审与 Golden 基线

状态：已完成。

验证证据（2026-07-14）：生成器通过 Python 生产 `SystemPromptBuilder`、`PromptAssembler` 和 `MessageEnvelopeBuilder` 生成 6 个共同投影 Case；连续生成两次后 Context Fixture 与 Manifest SHA-256 完全一致。修正 Profile 选择后，`-Pcompat` 聚焦执行 `GoldenManifestTest` 1 个测试并通过；Manifest 现包含 11 个业务夹具。

- 批准 Contract 的五项决定和默认上限。
- 扩展 Python Golden 生成器，使用临时 Workspace 和生产 Prompt helper 生成 `context/read-only-context-memory.json`。
- 连续生成两次，Fixture 与 Manifest Hash 必须一致。
- 只运行 Golden 结构/Manifest 聚焦验收，不运行完整 `compat`。

## Task C2：Kernel Memory 只读协议

状态：已完成。

验证证据（2026-07-14）：聚焦命令先因 Memory Mode、Profile、Retrieval Request/Result/Trace 与两个 Port 缺失而编译失败，随后实际执行 4 个协议测试并全部通过。Kernel 类型只使用 JDK 与既有消息类型；Retrieval Trace 只保留稳定状态和注入计数。

TDD 固定 Mode、Profile、Retrieval Request/Result 的不可变性、空值、上限前置字段与敏感字段边界。Kernel 只使用 JDK 类型。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-kernel -am \
  -Dtest=MemoryContextContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task C3：只读 Markdown Profile Adapter

状态：已完成。

验证证据（2026-07-14）：新增 `adapter-workspace` 模块后，聚焦命令先因 `MarkdownMemoryProfileAdapter` 与安全异常缺失而编译失败，随后实际执行 4 个测试并全部通过。测试只使用临时目录，覆盖缺失零写入、固定 UTF-8 文件、`Recent Turns` 截断、非普通文件、非法 UTF-8、超限和符号链接逃逸。

新增 `adapter-workspace` 模块。RED 覆盖缺失、空白、正常 UTF-8、Recent Turns 截断、非普通文件、符号链接逃逸、非法 UTF-8、超限和零写入。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapter-workspace -am \
  -Dtest=MarkdownMemoryProfileAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task C4：ContextAssembler 与 Context Frame

状态：已完成。

验证证据（2026-07-14）：聚焦命令先因 `ContextAssembler` 缺失而编译失败，随后实际执行 4 个测试并全部通过。实现固定了 Python 共同投影的 Section 顺序、System 分隔符、Context Frame Marker/警示语、临时消息位置、空段落省略、禁用段落和不可变输出。

RED 固定 System Section 顺序、分隔符、Frame Marker/警示语、历史/Frame/当前 User 顺序、空 Section 省略和输入不可变。实现不得依赖 Spring、NIO 或 Provider SDK。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=ContextAssemblerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task C5：Retrieval 注入与 ChatService 提交隔离

状态：已完成。

验证证据（2026-07-14）：聚焦命令先因 `MemoryContextService` 与稳定异常缺失而编译失败；实现注入后修正一处测试断言 API 用法，随后实际执行 4 个测试并全部通过。测试证明完整历史进入 Retrieval Request、裁剪历史进入模型、NoOp 结果不创建 Frame、Profile/检索/预算失败时模型与提交均为零、同一 Frame 保留到 Tool Loop 后续请求，且最终只提交真实 User/Assistant。

RED 固定：完整历史进入 Query、NoOp 返回空、Fake Result 进入 Frame、Retrieval 异常时模型零调用/Conversation 零提交、Frame 不持久化、Tool Loop 后续请求保留 Frame。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application -am \
  -Dtest=MemoryContextChatServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task C6：Bootstrap 配置与默认关闭装配

状态：待实施。

RED 固定配置默认值、上限校验、`DISABLED` 零文件访问、`READ_ONLY` 只读 Adapter 装配和 NoOp Retrieval。`.env.example` 继续显式 `DISABLED`。

聚焦命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-bootstrap -am \
  -Dtest=ApplicationConfigurationTest,MemoryConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task C7：Golden 兼容与故障分类

状态：待实施。

- Java 使用生产 `ContextAssembler` 和临时 Markdown Adapter 验证全部 Golden Case。
- 路径逃逸、编码、超限、Retrieval 故障和提交隔离进入 `failure` Profile。
- 更新 Contract、Roadmap、能力矩阵、README 与本地运行手册。

聚焦验收：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl agent-application,adapter-workspace -am \
  -Dtest=ReadOnlyContextMemoryGoldenTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task C8：阶段门禁、自审与提交

状态：待实施。

按顺序执行一次：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
./mvnw --batch-mode --no-transfer-progress -Pcompat verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
```

随后审计：

- 全 Reactor 禁止依赖、Secret 和 Workspace 跟踪。
- 生产 Bean 没有 Memory Writer、Optimizer、真实 Retrieval Engine 或记忆 Tool。
- 模板、本地部署均为 `AGENT_MEMORY_MODE=DISABLED`。
- `git diff --check`、错误脱敏、临时 Frame 不持久化和测试选择器。

不执行真实 Workspace 或真实模型 Smoke。

## 完成定义

- C1–C8 全部完成并记录准确测试数。
- 生产默认关闭，没有 Workspace 写入。
- Markdown Profile 和 Context Frame 共同投影通过 Golden。
- R4.2 缺口继续明确，不把 NoOp Retrieval 描述为语义检索已完成。
