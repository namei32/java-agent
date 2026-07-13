# Python/Java Golden Test 实施计划

- 状态：已实现并验证
- 日期：2026-07-13
- Spec：[Python/Java Golden Test 基线设计](../specs/2026-07-13-python-java-golden-test-design.md)

## Task G1：规范与生成器

- 落地夹具 Contract、Spec、目录和 Python 生成器。
- 固化历史、Prompt、SQLite 与错误迁移映射夹具。
- 连续生成两次并比较工作树，证明确定性。

聚焦验收：运行生成器两次，第二次 `git diff --exit-code -- testdata/golden`。

## Task G2：Java Golden 测试

- 配置 `golden.root` 和测试 JSON 依赖。
- 在 Kernel、Application、SQLite 和 Bootstrap 分别实现 `@Tag("compat")` 测试。
- 先添加测试并确认 `compat` 因缺失夹具读取/行为而失败，再实现最小测试基础设施并 GREEN；纯夹具验收不人为破坏生产代码。

聚焦命令：

```bash
./mvnw -Pcompat -Dtest='*Golden*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task G3：CI 与文档

- 新增 GitHub Actions 默认、`failure` 和 `compat` Job。
- 更新文档索引、Roadmap、能力矩阵和 README。
- CI 配置只运行离线测试，不注入模型密钥。

## 最终门禁

```bash
./mvnw spotless:apply
./mvnw clean verify
./mvnw -Pfailure verify
./mvnw -Pcompat verify
```

同时检查 Golden Hash、重复 Case ID、Secret、数据库/Workspace 跟踪文件和 Git diff。验证完成前不得把本计划或 Spec 标为“已实现并验证”。

## 实施结果

- Python 生成器连续运行两次，五个 Golden 文件的 SHA-256 字节一致。
- Manifest 校验、历史 5 Case、Prompt 2 Case、SQLite 1 Case、错误映射 9 Case 均通过。
- Golden 首次验收发现并修正 Java SQLite 整分钟时间省略秒的问题，以及 `ProblemDetail.type` 依赖隐式序列化默认的问题。
- 为避免 CI 偶发超时，把既有本地 OpenAI-compatible 桩测试的 50ms 超时窗口调整为 500ms；测试仍只访问本地桩。
- `./mvnw clean verify`、`./mvnw -Pfailure verify` 和 `./mvnw -Pcompat verify` 最终均退出码为 0；`compat` 共 98 个测试通过。
- Secret、运行时数据库/Workspace 文件、YAML 语法和 `git diff --check` 检查通过。
