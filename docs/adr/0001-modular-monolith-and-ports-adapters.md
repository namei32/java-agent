# ADR-0001：采用模块化单体与 Ports and Adapters

- 状态：已接受
- 日期：2026-07-12

## 背景

Python 基线同时包含 Agent Loop、持久化、模型、渠道、工具与后台任务。一次性迁移到分布式服务会同时改变语言、部署和一致性边界，使行为差异难以定位。

## 决策

Java 版本先采用一个进程、五个 Maven 模块的模块化单体：

- `agent-kernel`：领域值对象和 Port，只依赖 JDK。
- `agent-application`：用例编排、失败和并发语义。
- `adapter-sqlite`：显式 SQL 与事务。
- `adapter-spring-ai`：模型 SDK 协议转换。
- `agent-bootstrap`：Spring Boot 装配、HTTP、配置和运维入口。

依赖只能由外向内。框架类型不得进入核心模块。后续能力优先在现有边界内扩展；拆分服务必须由新的 ADR 说明收益和迁移成本。

## 结果

这使 Python/Java 行为可逐条对比，并保留未来替换框架或适配器的空间。代价是需要显式维护 Port、转换对象和模块依赖测试。
