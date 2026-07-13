# ADR-0004：使用 TomlJ 进行只读 TOML 解析

- 状态：已接受
- 日期：2026-07-13
- 关联 Contract：[Python/Java 配置兼容契约](../contracts/python-java-configuration.md)
- 关联 Spec：[Python/Java 配置兼容设计](../specs/2026-07-13-python-java-configuration-compatibility-design.md)

## 背景

Java 配置兼容层需要读取 Python `config.toml`，严格保留 TOML 原生类型并把语法错误转换为稳定诊断。第一阶段禁止写回配置、自动迁移和热更新，Parser 类型也不得越过 `agent-bootstrap` 边界。

选型复核日期为 2026-07-13，候选项如下：

| 候选项 | 优点 | 主要问题 |
| --- | --- | --- |
| TomlJ `1.1.1` | 专用 Parser；声明支持 TOML 1.0；提供错误位置和错误恢复；Apache-2.0 | 最新 Release 为 2024-01-01，仓库最后代码推送为 2024-09-19，维护节奏较低；引入 ANTLR Runtime 和 Checker Qual |
| Jackson TOML `3.1.2` | Apache-2.0；与项目现有 Jackson 3 体系一致；上游活跃 | 官方模块说明仍保留“实验性”表述，同时提供写入和 Data Binding，能力面超过本项目只读边界 |
| NightConfig `3.9.0` | 支持 TOML 1.0；维护活跃；支持注释和文件配置 | LGPL-3.0；可变配置、保存和自动保存 API 超出第一阶段需要，误写风险更高 |

核验来源：

- [TomlJ 官方仓库](https://github.com/tomlj/tomlj)和 [Maven Central 坐标](https://central.sonatype.com/artifact/org.tomlj/tomlj/1.1.1)。
- [Jackson TOML 官方模块](https://github.com/FasterXML/jackson-dataformats-text/tree/3.x/toml)和 [Maven Central 坐标](https://central.sonatype.com/artifact/tools.jackson.dataformat/jackson-dataformat-toml/3.1.2)。
- [NightConfig 官方仓库](https://github.com/TheElectronWill/night-config)。

## 决策

选择 `org.tomlj:tomlj:1.1.1`：

- 版本在父 POM 的 `tomlj.version` 统一管理。
- Task C1 只以 `test` Scope 加入 `agent-bootstrap`，用于读取配置 Golden 和验证 Parser 能力。
- Task C2 开始生产实现时，才把该依赖提升为 `agent-bootstrap` 运行时依赖。
- 生产代码只使用解析和查询 API，不封装或暴露任何写回能力。
- `TomlParseResult`、`TomlTable` 等第三方类型只能存在于 Bootstrap 配置包内部。

## 理由

TomlJ 的能力面最贴合当前需求：只负责解析、保留原生类型并提供结构化语法错误。Apache-2.0 许可证与当前项目兼容，JDK 21 兼容性由项目聚焦测试直接验证。

维护节奏较低是已知风险，但 TOML 1.0 规范稳定，且本项目通过 Golden 隔离 Parser 行为。相比引入可写配置对象或仍标注实验性的 Data Binding，当前选择更容易守住只读边界。

## 后果

- 新增 ANTLR Runtime 和 Checker Qual 传递依赖。
- 配置 Resolver 必须自行实现字段优先级、旧别名、环境变量、Provider 预设和诊断；不得依赖 Parser 做业务转换。
- 升级 TomlJ 前必须运行配置 Golden，并检查错误位置和原生类型是否变化。
- 如果未来 TomlJ 出现未修复的安全问题或不能支持已批准配置语法，可以用同一组 Golden 评估替换 Parser；替换需要更新本 ADR。
