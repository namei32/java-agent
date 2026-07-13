# 被动聊天 MVP 实施计划

> **执行智能体必读：** REQUIRED SUB-SKILL：使用 `subagent-driven-development`（推荐）或 `executing-plans`，按任务逐项实施。所有步骤使用 `- [ ]` 复选框跟踪。

**目标：** 使用 JDK 21、Maven、Spring Boot 4.1.0 和 Spring AI 2.0.0，实现一个可通过同步 HTTP API 完成被动聊天、恢复历史并将完整对话轮次原子写入 SQLite 的最小闭环。

**架构：** 项目采用五模块 Ports and Adapters 模块化单体。`agent-kernel` 定义领域模型和 Port，`agent-application` 编排用例与会话串行语义，两个 Adapter 分别连接 Spring AI 和 SQLite，`agent-bootstrap` 只负责 Spring Boot、HTTP、配置和装配。

**技术栈：** JDK 21、Maven Wrapper 3.9.16、Spring Boot 4.1.0、Spring AI 2.0.0、SQLite JDBC 3.53.2.0、JUnit Jupiter、AssertJ、Mockito、ArchUnit 1.4.2、Spotless 3.8.0、JaCoCo 0.8.15、Maven Enforcer 3.6.3。

## 全局约束

- Maven 坐标固定为 `io.namei.agent:namei-agent-parent:0.1.0-SNAPSHOT`。
- Java Compiler 的 `release` 固定为 21，禁止预览 API 和 `--enable-preview`。
- 必须使用 `./mvnw`；Maven Wrapper 固定使用 Maven 3.9.16。
- Spring Boot 固定为 4.1.0；Spring AI 固定为 2.0.0。
- `agent-kernel` 不得依赖 Spring、Spring AI、JDBC、Reactor 或模型提供方 SDK。
- `agent-application` 只能依赖 `agent-kernel`。
- SQLite 必须使用显式 SQL 和真实临时数据库测试，禁止使用 JPA、Spring JDBC、R2DBC 或 H2。
- Spring AI 只能实现项目自有的 `ChatModelPort`，不得控制聊天用例。
- 同一 `sessionId` 串行执行；不同会话允许并行；该保证仅限单进程。
- 模型或数据库失败时，本对话轮次必须零写入。
- 默认只监听 `127.0.0.1`，只开放 `/actuator/health`，不得记录 API Key、Prompt 或消息正文。
- 默认构建禁止访问真实模型，不需要 API Key。
- 文档使用中文；代码标识、协议、命令和必要专业术语保留英文。
- 每个生产行为严格执行 Red-Green-Refactor，提交前必须运行当前任务的目标测试。

## 已核实版本与官方依据

- Spring Boot 4.1.0：[Spring Boot 4.1.0 发布说明](https://spring.io/blog/2026/06/10/spring-boot-4/)
- Spring AI 2.0.0：[Spring AI 2.0.0 GA 发布说明](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- Spring AI OpenAI Starter：`spring-ai-starter-model-openai`，[官方参考](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
- Spring Boot 4.1 推荐 Spring MVC Starter：`spring-boot-starter-webmvc`，[官方教程](https://docs.spring.io/spring-boot/tutorial/first-application/index.html)
- Virtual Thread：`spring.threads.virtual.enabled=true`，[官方参考](https://docs.spring.io/spring-boot/reference/features/spring-application.html)
- Graceful Shutdown：[官方参考](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)

## Spec 覆盖矩阵

| Spec 章节 | 对应任务 |
|---|---|
| 1–2 目的与范围 | 任务 1–13 共同约束；任务 13 最终验收 |
| 3 架构 | 任务 1、3、7、11 |
| 4 Maven 坐标与目录 | 任务 1 |
| 5 HTTP Contract | 任务 9、13 |
| 6 应用流程与并发 | 任务 3、4、6、12 |
| 7 模型适配器 | 任务 7、10 |
| 8 Prompt 与历史消息 | 任务 2、3、8 |
| 9 配置与密钥 | 任务 8、9、11、13 |
| 10 SQLite 兼容性 | 任务 5、6、12 |
| 11 运行环境与安全边界 | 任务 8、9、11 |
| 12 可观测性 | 任务 9、11 |
| 13 构建规则 | 任务 1、12、13 |
| 14 测试策略 | 每个任务的 Red-Green 步骤；任务 10、12 |
| 15 验收标准 | 任务 12、13 |
| 16 交付流程 | 任务 13 |

## 文件结构映射

```text
java-agent/
|-- .gitignore
|-- .mvn/wrapper/maven-wrapper.properties
|-- mvnw
|-- mvnw.cmd
|-- pom.xml
|-- README.md
|-- agent-kernel/
|   |-- pom.xml
|   `-- src/{main,test}/java/io/namei/agent/kernel/...
|-- agent-application/
|   |-- pom.xml
|   `-- src/{main,test}/java/io/namei/agent/application/...
|-- adapter-sqlite/
|   |-- pom.xml
|   `-- src/{main,test}/java/io/namei/agent/adapter/sqlite/...
|-- adapter-spring-ai/
|   |-- pom.xml
|   `-- src/{main,test}/java/io/namei/agent/adapter/springai/...
`-- agent-bootstrap/
    |-- pom.xml
    `-- src/{main,test}/...
```

文件职责必须保持单一：领域值对象不包含框架逻辑；每个 Adapter 只做协议转换或持久化；Controller 不编排业务；配置类不实现业务规则。

---

### 任务 1：建立 Maven 骨架与最小领域模型

**文件：**

- 创建：`.gitignore`
- 创建：`pom.xml`
- 创建：`agent-kernel/pom.xml`
- 创建：`agent-application/pom.xml`
- 创建：`adapter-sqlite/pom.xml`
- 创建：`adapter-spring-ai/pom.xml`
- 创建：`agent-bootstrap/pom.xml`
- 生成：`.mvn/wrapper/maven-wrapper.properties`
- 生成：`mvnw`
- 生成：`mvnw.cmd`
- 创建：`agent-kernel/src/test/java/io/namei/agent/kernel/model/ChatMessageTest.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/model/MessageRole.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/model/ChatMessage.java`

**接口：**

- 产出：`MessageRole { SYSTEM, USER, ASSISTANT }`
- 产出：`ChatMessage(MessageRole role, String content)`；构造时拒绝 `null` 和空白内容，并去除首尾空白。

- [ ] **步骤 1：创建父 POM 和五个模块 POM**

父 POM 使用以下完整结构；插件执行放在父工程，版本只在此处声明：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
  </parent>

  <groupId>io.namei.agent</groupId>
  <artifactId>namei-agent-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>agent-kernel</module>
    <module>agent-application</module>
    <module>adapter-sqlite</module>
    <module>adapter-spring-ai</module>
    <module>agent-bootstrap</module>
  </modules>

  <properties>
    <java.version>21</java.version>
    <maven.version>3.9.16</maven.version>
    <spring-ai.version>2.0.0</spring-ai.version>
    <sqlite-jdbc.version>3.53.2.0</sqlite-jdbc.version>
    <archunit.version>1.4.2</archunit.version>
    <spotless.version>3.8.0</spotless.version>
    <jacoco.version>0.8.15</jacoco.version>
    <maven-enforcer.version>3.6.3</maven-enforcer.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>${sqlite-jdbc.version}</version>
      </dependency>
      <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>${archunit.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
      </plugins>
    </pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>${maven-enforcer.version}</version>
        <executions>
          <execution>
            <id>enforce-build</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireJavaVersion><version>[21,22)</version></requireJavaVersion>
                <requireMavenVersion><version>[3.9,4.0)</version></requireMavenVersion>
                <requireReleaseDeps><onlyWhenRelease>true</onlyWhenRelease></requireReleaseDeps>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>com.diffplug.spotless</groupId>
        <artifactId>spotless-maven-plugin</artifactId>
        <version>${spotless.version}</version>
        <configuration>
          <java><googleJavaFormat/><endWithNewline/></java>
          <formats>
            <format>
              <includes><include>*.md</include><include>**/*.md</include><include>**/pom.xml</include></includes>
              <trimTrailingWhitespace/><endWithNewline/>
            </format>
          </formats>
        </configuration>
        <executions><execution><goals><goal>check</goal></goals></execution></executions>
      </plugin>
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>${jacoco.version}</version>
        <executions>
          <execution><goals><goal>prepare-agent</goal></goals></execution>
          <execution><id>report</id><phase>verify</phase><goals><goal>report</goal></goals></execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <executions>
          <execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

每个子模块 POM 都继承父工程。依赖分别固定为：

```xml
<!-- agent-kernel/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.namei.agent</groupId><artifactId>namei-agent-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>agent-kernel</artifactId>
  <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency><dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency></dependencies>
</project>
```

```xml
<!-- agent-application/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.namei.agent</groupId><artifactId>namei-agent-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>agent-application</artifactId>
  <dependencies><dependency><groupId>io.namei.agent</groupId><artifactId>agent-kernel</artifactId><version>${project.version}</version></dependency><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency><dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency></dependencies>
</project>
```

```xml
<!-- adapter-sqlite/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.namei.agent</groupId><artifactId>namei-agent-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>adapter-sqlite</artifactId>
  <dependencies><dependency><groupId>io.namei.agent</groupId><artifactId>agent-kernel</artifactId><version>${project.version}</version></dependency><dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId></dependency><dependency><groupId>tools.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency><dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency></dependencies>
</project>
```

```xml
<!-- adapter-spring-ai/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.namei.agent</groupId><artifactId>namei-agent-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>adapter-spring-ai</artifactId>
  <dependencies><dependency><groupId>io.namei.agent</groupId><artifactId>agent-kernel</artifactId><version>${project.version}</version></dependency><dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-model</artifactId></dependency><dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId></dependency><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency></dependencies>
</project>
```

```xml
<!-- agent-bootstrap/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.namei.agent</groupId><artifactId>namei-agent-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>agent-bootstrap</artifactId>
  <dependencies>
    <dependency><groupId>io.namei.agent</groupId><artifactId>agent-application</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>io.namei.agent</groupId><artifactId>adapter-sqlite</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>io.namei.agent</groupId><artifactId>adapter-spring-ai</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-model-openai</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

- [ ] **步骤 2：生成 Maven Wrapper 并创建忽略规则**

运行：

```bash
mvn org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16 -Dtype=only-script
```

创建 `.gitignore`：

```gitignore
.idea/
.vscode/
.DS_Store
target/
**/target/
.env
workspace/
*.db
*.db-shm
*.db-wal
*.log
```

验证：`./mvnw --version`

预期：输出 `Apache Maven 3.9.16` 和 Java `21`。

- [ ] **步骤 3：先写 `ChatMessage` 失败测试**

```java
package io.namei.agent.kernel.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChatMessageTest {
  @Test
  void trimsContent() {
    assertThat(new ChatMessage(MessageRole.USER, "  你好  ").content()).isEqualTo("你好");
  }

  @Test
  void rejectsBlankContent() {
    assertThatThrownBy(() -> new ChatMessage(MessageRole.USER, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("消息内容不能为空");
  }
}
```

- [ ] **步骤 4：运行测试并确认按预期失败**

运行：`./mvnw -pl agent-kernel test -Dtest=ChatMessageTest`

预期：编译失败，提示找不到 `ChatMessage` 或 `MessageRole`。

- [ ] **步骤 5：实现最小领域类型**

```java
package io.namei.agent.kernel.model;

public enum MessageRole {
  SYSTEM,
  USER,
  ASSISTANT
}
```

```java
package io.namei.agent.kernel.model;

import java.util.Objects;

public record ChatMessage(MessageRole role, String content) {
  public ChatMessage {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
    content = content.trim();
    if (content.isEmpty()) {
      throw new IllegalArgumentException("消息内容不能为空");
    }
  }
}
```

- [ ] **步骤 6：运行目标测试和格式检查**

运行：

```bash
./mvnw -pl agent-kernel test -Dtest=ChatMessageTest
./mvnw spotless:apply
./mvnw -pl agent-kernel test
```

预期：全部成功，测试数为 2，失败数为 0。

- [ ] **步骤 7：提交**

```bash
git add .gitignore .mvn mvnw mvnw.cmd pom.xml agent-*/pom.xml adapter-*/pom.xml agent-kernel/src
git commit -m "build: 建立 Java Agent Maven 多模块骨架"
```

---

### 任务 2：实现完整对话轮次的历史窗口选择

**文件：**

- 创建：`agent-kernel/src/test/java/io/namei/agent/kernel/history/ConversationHistorySelectorTest.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/history/HistoryLimits.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/history/ConversationHistorySelector.java`

**接口：**

- 消费：`ChatMessage`、`MessageRole`
- 产出：`HistoryLimits(int maxMessages, int maxCharacters)`
- 产出：`List<ChatMessage> ConversationHistorySelector.select(List<ChatMessage>, HistoryLimits)`

- [ ] **步骤 1：编写窗口行为失败测试**

```java
package io.namei.agent.kernel.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationHistorySelectorTest {
  private final ConversationHistorySelector selector = new ConversationHistorySelector();

  @Test
  void keepsNewestCompleteTurnsWithinBothLimits() {
    var history = List.of(
        new ChatMessage(MessageRole.ASSISTANT, "孤立响应"),
        new ChatMessage(MessageRole.USER, "旧问题123"),
        new ChatMessage(MessageRole.ASSISTANT, "旧回答123"),
        new ChatMessage(MessageRole.USER, "新问题"),
        new ChatMessage(MessageRole.ASSISTANT, "新回答"));

    assertThat(selector.select(history, new HistoryLimits(4, 6)))
        .containsExactly(
            new ChatMessage(MessageRole.USER, "新问题"),
            new ChatMessage(MessageRole.ASSISTANT, "新回答"));
  }

  @Test
  void neverReturnsAnOrphanAssistantMessage() {
    assertThat(selector.select(
            List.of(new ChatMessage(MessageRole.ASSISTANT, "孤立响应")),
            new HistoryLimits(40, 100_000)))
        .isEmpty();
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl agent-kernel test -Dtest=ConversationHistorySelectorTest`

预期：编译失败，提示缺少 `ConversationHistorySelector` 和 `HistoryLimits`。

- [ ] **步骤 3：实现最小窗口算法**

```java
package io.namei.agent.kernel.history;

public record HistoryLimits(int maxMessages, int maxCharacters) {
  public HistoryLimits {
    if (maxMessages < 0 || maxCharacters < 0) {
      throw new IllegalArgumentException("历史窗口限制不能为负数");
    }
  }
}
```

```java
package io.namei.agent.kernel.history;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConversationHistorySelector {
  public List<ChatMessage> select(List<ChatMessage> history, HistoryLimits limits) {
    var selectedReversed = new ArrayList<ChatMessage>();
    int characters = 0;
    int index = history.size() - 1;
    while (index > 0) {
      ChatMessage assistant = history.get(index);
      ChatMessage user = history.get(index - 1);
      if (assistant.role() != MessageRole.ASSISTANT || user.role() != MessageRole.USER) {
        index--;
        continue;
      }
      int pairCharacters = user.content().length() + assistant.content().length();
      if (selectedReversed.size() + 2 > limits.maxMessages()
          || characters + pairCharacters > limits.maxCharacters()) {
        break;
      }
      selectedReversed.add(assistant);
      selectedReversed.add(user);
      characters += pairCharacters;
      index -= 2;
    }
    Collections.reverse(selectedReversed);
    return List.copyOf(selectedReversed);
  }
}
```

- [ ] **步骤 4：运行测试并确认通过**

运行：`./mvnw -pl agent-kernel test -Dtest=ConversationHistorySelectorTest`

预期：2 个测试通过。

- [ ] **步骤 5：提交**

```bash
git add agent-kernel/src/main/java/io/namei/agent/kernel/history agent-kernel/src/test/java/io/namei/agent/kernel/history
git commit -m "feat: 增加完整对话轮次历史窗口"
```

---

### 任务 3：定义 Port 并实现聊天应用用例

**文件：**

- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/model/SessionSnapshot.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/model/PersistedTurn.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/model/ChatModelRequest.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/model/ChatModelResponse.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/port/ChatModelPort.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/port/SessionRepository.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/error/InvalidModelResponseException.java`
- 创建：`agent-application/src/main/java/io/namei/agent/application/ChatCommand.java`
- 创建：`agent-application/src/main/java/io/namei/agent/application/ChatResult.java`
- 创建：`agent-application/src/main/java/io/namei/agent/application/ChatUseCase.java`
- 创建：`agent-application/src/main/java/io/namei/agent/application/SessionExecutionGate.java`
- 创建：`agent-application/src/main/java/io/namei/agent/application/ChatService.java`
- 创建：`agent-application/src/test/java/io/namei/agent/application/ChatServiceTest.java`

**接口：**

- 产出：`ChatModelResponse ChatModelPort.generate(ChatModelRequest request)`
- 产出：`SessionSnapshot SessionRepository.load(String sessionId)`
- 产出：`void SessionRepository.appendTurn(String sessionId, PersistedTurn turn)`
- 产出：`ChatResult ChatUseCase.chat(ChatCommand command)`
- 产出：`<T> T SessionExecutionGate.execute(String sessionId, Supplier<T> action)`

- [ ] **步骤 1：先写成功和失败零写入测试**

```java
package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.*;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ChatServiceTest {
  @Test
  void buildsPromptAndPersistsOneCompleteTurn() {
    var repository = new RecordingRepository(List.of(
        new ChatMessage(MessageRole.USER, "第一问"),
        new ChatMessage(MessageRole.ASSISTANT, "第一答")));
    var model = new RecordingModel(new ChatModelResponse("第二答"));
    var service = service(repository, model);

    ChatResult result = service.chat(new ChatCommand("demo", "第二问"));

    assertThat(result.assistant().content()).isEqualTo("第二答");
    assertThat(model.request.messages()).containsExactly(
        new ChatMessage(MessageRole.SYSTEM, "你是 Namei Agent。"),
        new ChatMessage(MessageRole.USER, "第一问"),
        new ChatMessage(MessageRole.ASSISTANT, "第一答"),
        new ChatMessage(MessageRole.USER, "第二问"));
    assertThat(repository.turns).hasSize(1);
  }

  @Test
  void doesNotPersistWhenModelFails() {
    var repository = new RecordingRepository(List.of());
    ChatModelPort model = request -> { throw new IllegalStateException("upstream"); };

    assertThatThrownBy(() -> service(repository, model).chat(new ChatCommand("demo", "问题")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.turns).isEmpty();
  }

  private ChatService service(RecordingRepository repository, ChatModelPort model) {
    SessionExecutionGate direct = new SessionExecutionGate() {
      @Override public <T> T execute(String sessionId, Supplier<T> action) { return action.get(); }
    };
    return new ChatService(repository, model, new ConversationHistorySelector(),
        new HistoryLimits(40, 100_000), direct, "你是 Namei Agent。",
        Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC));
  }

  private static final class RecordingModel implements ChatModelPort {
    private final ChatModelResponse response;
    private ChatModelRequest request;
    private RecordingModel(ChatModelResponse response) { this.response = response; }
    @Override public ChatModelResponse generate(ChatModelRequest request) {
      this.request = request;
      return response;
    }
  }

  private static final class RecordingRepository implements SessionRepository {
    private final List<ChatMessage> history;
    private final List<PersistedTurn> turns = new ArrayList<>();
    private RecordingRepository(List<ChatMessage> history) { this.history = history; }
    @Override public SessionSnapshot load(String sessionId) {
      return new SessionSnapshot(sessionId, history, history.size());
    }
    @Override public void appendTurn(String sessionId, PersistedTurn turn) { turns.add(turn); }
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl agent-application -am test -Dtest=ChatServiceTest`

预期：编译失败，提示缺少用例、Port 和模型类型。

- [ ] **步骤 3：创建领域记录和 Port**

```java
// SessionSnapshot.java
package io.namei.agent.kernel.model;

import java.util.List;
import java.util.Objects;

public record SessionSnapshot(String sessionId, List<ChatMessage> messages, long nextSequence) {
  public SessionSnapshot {
    Objects.requireNonNull(sessionId, "sessionId");
    messages = List.copyOf(messages);
    if (nextSequence < 0) throw new IllegalArgumentException("nextSequence 不能为负数");
  }
}
```

```java
// PersistedTurn.java
package io.namei.agent.kernel.model;

import java.time.OffsetDateTime;
import java.util.Objects;

public record PersistedTurn(
    ChatMessage user, OffsetDateTime userAt,
    ChatMessage assistant, OffsetDateTime assistantAt) {
  public PersistedTurn {
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(userAt, "userAt");
    Objects.requireNonNull(assistant, "assistant");
    Objects.requireNonNull(assistantAt, "assistantAt");
    if (user.role() != MessageRole.USER || assistant.role() != MessageRole.ASSISTANT) {
      throw new IllegalArgumentException("持久化轮次必须由 user 和 assistant 组成");
    }
  }
}
```

```java
// ChatModelRequest.java
package io.namei.agent.kernel.model;

import java.util.List;

public record ChatModelRequest(List<ChatMessage> messages) {
  public ChatModelRequest {
    messages = List.copyOf(messages);
  }
}
```

```java
// ChatModelResponse.java
package io.namei.agent.kernel.model;

import java.util.Objects;

public record ChatModelResponse(String content) {
  public ChatModelResponse {
    Objects.requireNonNull(content, "content");
  }
}
```

```java
package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;

@FunctionalInterface
public interface ChatModelPort {
  ChatModelResponse generate(ChatModelRequest request);
}
```

```java
package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;

public interface SessionRepository {
  SessionSnapshot load(String sessionId);
  void appendTurn(String sessionId, PersistedTurn turn);
}
```

- [ ] **步骤 4：实现应用用例**

```java
// ChatCommand.java
package io.namei.agent.application;

import java.util.Objects;

public record ChatCommand(String sessionId, String message) {
  public ChatCommand {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(message, "message");
  }
}
```

```java
// ChatResult.java
package io.namei.agent.application;

import io.namei.agent.kernel.model.ChatMessage;

public record ChatResult(String sessionId, ChatMessage assistant) {}
```

```java
// ChatUseCase.java
package io.namei.agent.application;

@FunctionalInterface
public interface ChatUseCase {
  ChatResult chat(ChatCommand command);
}
```

```java
// SessionExecutionGate.java
package io.namei.agent.application;

import java.util.function.Supplier;

public interface SessionExecutionGate {
  <T> T execute(String sessionId, Supplier<T> action);
}
```

```java
// InvalidModelResponseException.java
package io.namei.agent.kernel.error;

public final class InvalidModelResponseException extends RuntimeException {
  public InvalidModelResponseException(String message) { super(message); }
}
```

```java
// ChatService.java
package io.namei.agent.application;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.model.*;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;

public final class ChatService implements ChatUseCase {
  private final SessionRepository sessions;
  private final ChatModelPort model;
  private final ConversationHistorySelector historySelector;
  private final HistoryLimits limits;
  private final SessionExecutionGate gate;
  private final String systemPrompt;
  private final Clock clock;

  public ChatService(SessionRepository sessions, ChatModelPort model,
      ConversationHistorySelector historySelector, HistoryLimits limits,
      SessionExecutionGate gate, String systemPrompt, Clock clock) {
    this.sessions = sessions;
    this.model = model;
    this.historySelector = historySelector;
    this.limits = limits;
    this.gate = gate;
    this.systemPrompt = systemPrompt;
    this.clock = clock;
  }

  @Override
  public ChatResult chat(ChatCommand command) {
    return gate.execute(command.sessionId(), () -> execute(command));
  }

  private ChatResult execute(ChatCommand command) {
    var snapshot = sessions.load(command.sessionId());
    var user = new ChatMessage(MessageRole.USER, command.message());
    var messages = new ArrayList<ChatMessage>();
    messages.add(new ChatMessage(MessageRole.SYSTEM, systemPrompt));
    messages.addAll(historySelector.select(snapshot.messages(), limits));
    messages.add(user);
    OffsetDateTime userAt = OffsetDateTime.now(clock);
    var response = model.generate(new ChatModelRequest(messages));
    if (response == null || response.content().isBlank()) {
      throw new InvalidModelResponseException("模型返回了空响应");
    }
    var assistant = new ChatMessage(MessageRole.ASSISTANT, response.content());
    var turn = new PersistedTurn(user, userAt, assistant, OffsetDateTime.now(clock));
    sessions.appendTurn(command.sessionId(), turn);
    return new ChatResult(command.sessionId(), assistant);
  }
}
```

- [ ] **步骤 5：运行应用与 Kernel 测试**

运行：`./mvnw -pl agent-application -am test`

预期：`ChatServiceTest` 和前序 Kernel 测试全部通过。

- [ ] **步骤 6：提交**

```bash
git add agent-kernel/src/main/java/io/namei/agent/kernel agent-application/src
git commit -m "feat: 实现被动聊天应用用例"
```

---

### 任务 4：实现可回收的同会话串行执行闸门

**文件：**

- 创建：`agent-application/src/main/java/io/namei/agent/application/SessionLockTimeoutException.java`
- 创建：`agent-application/src/main/java/io/namei/agent/application/KeyedSessionExecutionGate.java`
- 创建：`agent-application/src/test/java/io/namei/agent/application/KeyedSessionExecutionGateTest.java`

**接口：**

- 消费：`SessionExecutionGate`
- 产出：`KeyedSessionExecutionGate(Duration waitTimeout)`
- 保证：同一 Key 串行、不同 Key 并行、超时抛出 `SessionLockTimeoutException`、空闲 Entry 被回收。

- [ ] **步骤 1：编写并发和回收失败测试**

```java
package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KeyedSessionExecutionGateTest {
  @Test
  void serializesSameSessionAndReclaimsEntry() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofSeconds(2));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> gate.execute("same", () -> {
        entered.countDown();
        await(release);
        return "first";
      }));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(() -> gate.execute("same", () -> "second"));
      Thread.sleep(50);
      assertThat(second.isDone()).isFalse();
      release.countDown();
      assertThat(first.get()).isEqualTo("first");
      assertThat(second.get()).isEqualTo("second");
    }
    assertThat(gate.activeEntryCount()).isZero();
  }

  @Test
  void allowsDifferentSessionsToOverlap() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofSeconds(1));
    var bothEntered = new CountDownLatch(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var one = executor.submit(() -> gate.execute("one", () -> awaitBoth(bothEntered)));
      var two = executor.submit(() -> gate.execute("two", () -> awaitBoth(bothEntered)));
      assertThat(one.get()).isEqualTo("done");
      assertThat(two.get()).isEqualTo("done");
    }
  }

  @Test
  void timesOutWaitingForBusySession() throws Exception {
    var gate = new KeyedSessionExecutionGate(Duration.ofMillis(30));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> gate.execute("same", () -> {
        entered.countDown();
        await(release);
        return "first";
      }));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> gate.execute("same", () -> "second"))
          .isInstanceOf(SessionLockTimeoutException.class);
      release.countDown();
      first.get();
    }
  }

  private static String awaitBoth(CountDownLatch latch) {
    latch.countDown();
    await(latch);
    return "done";
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("等待超时");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl agent-application -am test -Dtest=KeyedSessionExecutionGateTest`

预期：编译失败，提示缺少 `KeyedSessionExecutionGate`。

- [ ] **步骤 3：实现带引用计数的锁注册表**

```java
package io.namei.agent.application;

public final class SessionLockTimeoutException extends RuntimeException {
  public SessionLockTimeoutException(String sessionId) {
    super("等待会话执行许可超时: " + sessionId);
  }
}
```

```java
package io.namei.agent.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class KeyedSessionExecutionGate implements SessionExecutionGate {
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final Duration waitTimeout;

  public KeyedSessionExecutionGate(Duration waitTimeout) {
    this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
  }

  @Override
  public <T> T execute(String sessionId, Supplier<T> action) {
    Entry entry = entries.compute(sessionId, (key, current) -> {
      Entry selected = current == null ? new Entry() : current;
      selected.references.incrementAndGet();
      return selected;
    });
    boolean acquired = false;
    try {
      acquired = entry.lock.tryLock(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!acquired) throw new SessionLockTimeoutException(sessionId);
      return action.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SessionLockTimeoutException(sessionId);
    } finally {
      if (acquired) entry.lock.unlock();
      entries.compute(sessionId, (key, current) -> {
        if (current != entry) return current;
        return entry.references.decrementAndGet() == 0 ? null : entry;
      });
    }
  }

  int activeEntryCount() {
    return entries.size();
  }

  private static final class Entry {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final AtomicInteger references = new AtomicInteger();
  }
}
```

- [ ] **步骤 4：运行测试并确认通过**

运行：`./mvnw -pl agent-application -am test -Dtest=KeyedSessionExecutionGateTest`

预期：3 个测试通过，进程在 5 秒内退出。

- [ ] **步骤 5：提交**

```bash
git add agent-application/src/main/java/io/namei/agent/application/KeyedSessionExecutionGate.java agent-application/src/main/java/io/namei/agent/application/SessionLockTimeoutException.java agent-application/src/test/java/io/namei/agent/application/KeyedSessionExecutionGateTest.java
git commit -m "feat: 保证同会话请求串行执行"
```

---

### 任务 5：初始化并校验 Python-compatible SQLite Schema

**文件：**

- 创建：`adapter-sqlite/src/main/java/io/namei/agent/adapter/sqlite/SqliteRepositoryException.java`
- 创建：`adapter-sqlite/src/main/java/io/namei/agent/adapter/sqlite/SqliteSchemaInitializer.java`
- 创建：`adapter-sqlite/src/test/java/io/namei/agent/adapter/sqlite/SqliteSchemaInitializerTest.java`

**接口：**

- 产出：`void SqliteSchemaInitializer.initialize()`
- 产出：`Connection SqliteSchemaInitializer.openConnection()`，供 Repository 复用相同的 URL 和 `busy_timeout`。

- [ ] **步骤 1：编写新库初始化和不兼容 Schema 失败测试**

```java
package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSchemaInitializerTest {
  @TempDir Path tempDir;

  @Test
  void createsPythonCompatibleCoreTables() throws Exception {
    var initializer = new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    initializer.initialize();
    try (var connection = initializer.openConnection()) {
      assertThat(columns(connection, "sessions"))
          .contains("key", "created_at", "updated_at", "last_consolidated", "metadata",
              "last_user_at", "last_proactive_at", "next_seq");
      assertThat(columns(connection, "messages"))
          .contains("id", "session_key", "seq", "role", "content", "tool_chain", "extra", "ts");
    }
  }

  @Test
  void rejectsAnExistingTableMissingRequiredColumns() throws Exception {
    Path database = tempDir.resolve("broken.db");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      connection.createStatement().execute("CREATE TABLE sessions (key TEXT PRIMARY KEY)");
    }
    assertThatThrownBy(() -> new SqliteSchemaInitializer(database, 5_000).initialize())
        .isInstanceOf(SqliteRepositoryException.class)
        .hasMessageContaining("sessions 缺少必需列");
  }

  private static java.util.Set<String> columns(java.sql.Connection connection, String table)
      throws Exception {
    var names = new java.util.HashSet<String>();
    try (var rows = connection.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) names.add(rows.getString("name"));
    }
    return names;
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl adapter-sqlite -am test -Dtest=SqliteSchemaInitializerTest`

预期：编译失败，提示缺少 Schema Initializer。

- [ ] **步骤 3：实现显式 Schema 和兼容性检查**

```java
package io.namei.agent.adapter.sqlite;

public final class SqliteRepositoryException extends RuntimeException {
  public SqliteRepositoryException(String message, Throwable cause) { super(message, cause); }
  public SqliteRepositoryException(String message) { super(message); }
}
```

```java
package io.namei.agent.adapter.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

public final class SqliteSchemaInitializer {
  private static final Set<String> SESSION_COLUMNS = Set.of(
      "key", "created_at", "updated_at", "last_consolidated", "metadata",
      "last_user_at", "last_proactive_at", "next_seq");
  private static final Set<String> MESSAGE_COLUMNS = Set.of(
      "id", "session_key", "seq", "role", "content", "tool_chain", "extra", "ts");

  private final String jdbcUrl;
  private final int busyTimeoutMillis;

  public SqliteSchemaInitializer(Path database, int busyTimeoutMillis) {
    this.jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath();
    this.busyTimeoutMillis = busyTimeoutMillis;
  }

  public void initialize() {
    try (var connection = openConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS sessions (
            key TEXT PRIMARY KEY, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
            last_consolidated INTEGER NOT NULL DEFAULT 0, metadata TEXT,
            last_user_at TEXT, last_proactive_at TEXT,
            next_seq INTEGER NOT NULL DEFAULT 0)
          """);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY, session_key TEXT NOT NULL, seq INTEGER NOT NULL,
            role TEXT NOT NULL, content TEXT, tool_chain TEXT, extra TEXT,
            ts TEXT NOT NULL, UNIQUE(session_key, seq))
          """);
      requireColumns(connection, "sessions", SESSION_COLUMNS);
      requireColumns(connection, "messages", MESSAGE_COLUMNS);
    } catch (SQLException exception) {
      if (exception instanceof java.sql.SQLSyntaxErrorException) throw new SqliteRepositoryException("Schema 初始化失败", exception);
      throw new SqliteRepositoryException(exception.getMessage(), exception);
    }
  }

  public Connection openConnection() throws SQLException {
    Connection connection = DriverManager.getConnection(jdbcUrl);
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
    }
    return connection;
  }

  private static void requireColumns(Connection connection, String table, Set<String> required)
      throws SQLException {
    var actual = new HashSet<String>();
    try (var rows = connection.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) actual.add(rows.getString("name"));
    }
    var missing = new HashSet<>(required);
    missing.removeAll(actual);
    if (!missing.isEmpty()) throw new SqliteRepositoryException(table + " 缺少必需列: " + missing);
  }
}
```

`CREATE TABLE IF NOT EXISTS` 对已有表不做修改，后续 `requireColumns` 必须拒绝缺列的旧表。不得自动删除、重建或补写已有表。

- [ ] **步骤 4：运行测试并确认通过**

运行：`./mvnw -pl adapter-sqlite -am test -Dtest=SqliteSchemaInitializerTest`

预期：2 个测试通过。

- [ ] **步骤 5：提交**

```bash
git add adapter-sqlite/src
git commit -m "feat: 初始化兼容 Python 的 SQLite Schema"
```

---

### 任务 6：实现 Session 读取与完整对话轮次原子写入

**文件：**

- 创建：`adapter-sqlite/src/main/java/io/namei/agent/adapter/sqlite/JdbcSessionRepository.java`
- 创建：`adapter-sqlite/src/test/java/io/namei/agent/adapter/sqlite/JdbcSessionRepositoryTest.java`

**接口：**

- 消费：`SessionRepository`、`SessionSnapshot`、`PersistedTurn`、`SqliteSchemaInitializer`
- 产出：`JdbcSessionRepository implements SessionRepository`
- 额外产出：`boolean JdbcSessionRepository.isAvailable()`，仅供 Bootstrap Health Indicator 使用。

- [ ] **步骤 1：编写读取、重启恢复和回滚失败测试**

```java
package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.*;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcSessionRepositoryTest {
  @TempDir Path tempDir;
  private SqliteSchemaInitializer schema;
  private JdbcSessionRepository repository;

  @BeforeEach
  void setUp() {
    schema = new SqliteSchemaInitializer(tempDir.resolve("sessions.db"), 5_000);
    schema.initialize();
    repository = new JdbcSessionRepository(schema);
  }

  @Test
  void storesTwoMessagesWithPythonCompatibleIdsAndRestoresThem() throws Exception {
    OffsetDateTime now = OffsetDateTime.parse("2026-07-13T08:00:00+08:00");
    repository.appendTurn("demo", new PersistedTurn(
        new ChatMessage(MessageRole.USER, "你好"), now,
        new ChatMessage(MessageRole.ASSISTANT, "你好"), now.plusSeconds(1)));

    var reopened = new JdbcSessionRepository(schema).load("demo");
    assertThat(reopened.nextSequence()).isEqualTo(2);
    assertThat(reopened.messages()).containsExactly(
        new ChatMessage(MessageRole.USER, "你好"),
        new ChatMessage(MessageRole.ASSISTANT, "你好"));
    try (var connection = schema.openConnection();
         var rows = connection.createStatement().executeQuery(
             "SELECT id, seq FROM messages ORDER BY seq")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("id")).isEqualTo("demo:0");
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("id")).isEqualTo("demo:1");
    }
  }

  @Test
  void rollsBackUserMessageWhenAssistantInsertFails() throws Exception {
    try (var connection = schema.openConnection()) {
      connection.createStatement().execute("""
          CREATE TRIGGER fail_assistant BEFORE INSERT ON messages
          WHEN NEW.role = 'assistant' BEGIN SELECT RAISE(ABORT, 'boom'); END
          """);
    }
    OffsetDateTime now = OffsetDateTime.parse("2026-07-13T08:00:00+08:00");
    assertThatThrownBy(() -> repository.appendTurn("demo", new PersistedTurn(
        new ChatMessage(MessageRole.USER, "问题"), now,
        new ChatMessage(MessageRole.ASSISTANT, "回答"), now)))
        .isInstanceOf(SqliteRepositoryException.class);
    assertThat(repository.load("demo").messages()).isEmpty();
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl adapter-sqlite -am test -Dtest=JdbcSessionRepositoryTest`

预期：编译失败，提示缺少 `JdbcSessionRepository`。

- [ ] **步骤 3：实现读取和单事务写入**

```java
package io.namei.agent.adapter.sqlite;

import io.namei.agent.kernel.model.*;
import io.namei.agent.kernel.port.SessionRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

public final class JdbcSessionRepository implements SessionRepository {
  private final SqliteSchemaInitializer schema;

  public JdbcSessionRepository(SqliteSchemaInitializer schema) {
    this.schema = schema;
  }

  @Override
  public SessionSnapshot load(String sessionId) {
    try (var connection = schema.openConnection()) {
      long nextSequence = readNextSequence(connection, sessionId);
      var messages = new ArrayList<ChatMessage>();
      try (var statement = connection.prepareStatement(
          "SELECT role, content FROM messages WHERE session_key = ? ORDER BY seq")) {
        statement.setString(1, sessionId);
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            String role = rows.getString("role");
            if ("user".equals(role)) messages.add(new ChatMessage(MessageRole.USER, rows.getString("content")));
            if ("assistant".equals(role)) messages.add(new ChatMessage(MessageRole.ASSISTANT, rows.getString("content")));
          }
        }
      }
      return new SessionSnapshot(sessionId, messages, nextSequence);
    } catch (SQLException exception) {
      throw new SqliteRepositoryException("读取 Session 失败", exception);
    }
  }

  @Override
  public void appendTurn(String sessionId, PersistedTurn turn) {
    try (var connection = schema.openConnection()) {
      connection.setAutoCommit(false);
      try {
        long next = ensureSessionAndReadNext(connection, sessionId, turn.userAt().toString());
        insertMessage(connection, sessionId, next, turn.user(), turn.userAt().toString());
        insertMessage(connection, sessionId, next + 1, turn.assistant(), turn.assistantAt().toString());
        try (var update = connection.prepareStatement("""
            UPDATE sessions SET next_seq = ?, updated_at = ?, last_user_at = ? WHERE key = ?
            """)) {
          update.setLong(1, next + 2);
          update.setString(2, turn.assistantAt().toString());
          update.setString(3, turn.userAt().toString());
          update.setString(4, sessionId);
          update.executeUpdate();
        }
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      }
    } catch (Exception exception) {
      throw new SqliteRepositoryException("写入完整对话轮次失败", exception);
    }
  }

  public boolean isAvailable() {
    try (var connection = schema.openConnection();
         var rows = connection.createStatement().executeQuery("SELECT 1")) {
      return rows.next() && rows.getInt(1) == 1;
    } catch (SQLException exception) {
      return false;
    }
  }

  private static long readNextSequence(Connection connection, String sessionId) throws SQLException {
    long fromSession = 0;
    try (var statement = connection.prepareStatement("SELECT next_seq FROM sessions WHERE key = ?")) {
      statement.setString(1, sessionId);
      try (var row = statement.executeQuery()) { if (row.next()) fromSession = row.getLong(1); }
    }
    long fromMessages = 0;
    try (var statement = connection.prepareStatement(
        "SELECT COALESCE(MAX(seq) + 1, 0) FROM messages WHERE session_key = ?")) {
      statement.setString(1, sessionId);
      try (var row = statement.executeQuery()) { if (row.next()) fromMessages = row.getLong(1); }
    }
    return Math.max(fromSession, fromMessages);
  }

  private static long ensureSessionAndReadNext(Connection connection, String sessionId, String now)
      throws SQLException {
    try (var insert = connection.prepareStatement("""
        INSERT INTO sessions (key, created_at, updated_at, last_consolidated, metadata,
          last_user_at, last_proactive_at, next_seq)
        VALUES (?, ?, ?, 0, '{}', NULL, NULL, 0)
        ON CONFLICT(key) DO NOTHING
        """)) {
      insert.setString(1, sessionId);
      insert.setString(2, now);
      insert.setString(3, now);
      insert.executeUpdate();
    }
    return readNextSequence(connection, sessionId);
  }

  private static void insertMessage(Connection connection, String sessionId, long sequence,
      ChatMessage message, String timestamp) throws SQLException {
    try (var insert = connection.prepareStatement("""
        INSERT INTO messages (id, session_key, seq, role, content, tool_chain, extra, ts)
        VALUES (?, ?, ?, ?, ?, NULL, '{}', ?)
        """)) {
      insert.setString(1, sessionId + ":" + sequence);
      insert.setString(2, sessionId);
      insert.setLong(3, sequence);
      insert.setString(4, message.role().name().toLowerCase(java.util.Locale.ROOT));
      insert.setString(5, message.content());
      insert.setString(6, timestamp);
      insert.executeUpdate();
    }
  }
}
```

- [ ] **步骤 4：运行测试并确认通过**

运行：`./mvnw -pl adapter-sqlite -am test -Dtest=JdbcSessionRepositoryTest`

预期：2 个测试通过；故障 Trigger 测试验证消息数为 0。

- [ ] **步骤 5：提交**

```bash
git add adapter-sqlite/src
git commit -m "feat: 原子持久化完整聊天轮次"
```

---

### 任务 7：实现 Spring AI 模型适配器

**文件：**

- 修改：`adapter-spring-ai/pom.xml`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/error/ModelInvocationException.java`
- 创建：`agent-kernel/src/main/java/io/namei/agent/kernel/error/ModelTimeoutException.java`
- 创建：`adapter-spring-ai/src/main/java/io/namei/agent/adapter/springai/SpringAiChatModelAdapter.java`
- 创建：`adapter-spring-ai/src/main/java/io/namei/agent/adapter/springai/SpringAiAdapterConfiguration.java`
- 创建：`adapter-spring-ai/src/test/java/io/namei/agent/adapter/springai/SpringAiChatModelAdapterTest.java`

**接口：**

- 消费：Spring AI `ChatModel`
- 产出：`SpringAiChatModelAdapter implements ChatModelPort`
- 异常：超时转换为 `ModelTimeoutException`；其余调用失败转换为 `ModelInvocationException`；空响应转换为 `InvalidModelResponseException`。

- [ ] **步骤 1：补充测试依赖并编写适配器失败测试**

在 `adapter-spring-ai/pom.xml` 的测试依赖中加入 `spring-boot-starter-test`；不得在此模块加入 OpenAI Starter，自动配置只属于 Bootstrap。

```java
package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiChatModelAdapterTest {
  @Test
  void mapsProjectMessagesAndReturnsAssistantText() {
    ChatModel chatModel = mock(ChatModel.class);
    ChatResponse response = mock(ChatResponse.class);
    Generation generation = mock(Generation.class);
    AssistantMessage output = mock(AssistantMessage.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(response);
    when(response.getResult()).thenReturn(generation);
    when(generation.getOutput()).thenReturn(output);
    when(output.getText()).thenReturn("  回答  ");

    var adapter = new SpringAiChatModelAdapter(chatModel);
    var result = adapter.generate(new ChatModelRequest(List.of(
        new ChatMessage(MessageRole.SYSTEM, "系统"),
        new ChatMessage(MessageRole.USER, "问题"))));

    assertThat(result.content()).isEqualTo("回答");
    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    assertThat(prompt.getValue().getInstructions()).hasSize(2);
  }

  @Test
  void rejectsMissingGeneration() {
    ChatModel chatModel = mock(ChatModel.class);
    ChatResponse response = mock(ChatResponse.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(response);
    when(response.getResult()).thenReturn(null);

    assertThatThrownBy(() -> new SpringAiChatModelAdapter(chatModel).generate(
        new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")))))
        .isInstanceOf(InvalidModelResponseException.class);
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl adapter-spring-ai -am test -Dtest=SpringAiChatModelAdapterTest`

预期：编译失败，提示缺少适配器和模型异常类型。

- [ ] **步骤 3：实现异常与消息映射**

```java
package io.namei.agent.kernel.error;

public final class ModelInvocationException extends RuntimeException {
  public ModelInvocationException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package io.namei.agent.kernel.error;

public final class ModelTimeoutException extends RuntimeException {
  public ModelTimeoutException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.*;
import io.namei.agent.kernel.port.ChatModelPort;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

public final class SpringAiChatModelAdapter implements ChatModelPort {
  private final ChatModel chatModel;

  public SpringAiChatModelAdapter(ChatModel chatModel) {
    this.chatModel = chatModel;
  }

  @Override
  public ChatModelResponse generate(ChatModelRequest request) {
    try {
      List<Message> instructions = request.messages().stream().map(this::toSpringMessage).toList();
      var response = chatModel.call(new Prompt(instructions));
      if (response == null || response.getResult() == null
          || response.getResult().getOutput() == null) {
        throw new InvalidModelResponseException("模型响应缺少 Generation");
      }
      String text = response.getResult().getOutput().getText();
      if (text == null || text.isBlank()) {
        throw new InvalidModelResponseException("模型返回了空响应");
      }
      return new ChatModelResponse(text.trim());
    } catch (InvalidModelResponseException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      if (hasTimeoutCause(exception)) {
        throw new ModelTimeoutException("模型调用超时", exception);
      }
      throw new ModelInvocationException("模型调用失败", exception);
    }
  }

  private Message toSpringMessage(ChatMessage message) {
    return switch (message.role()) {
      case SYSTEM -> new SystemMessage(message.content());
      case USER -> new UserMessage(message.content());
      case ASSISTANT -> new AssistantMessage(message.content());
    };
  }

  private static boolean hasTimeoutCause(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
          || current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
        return true;
      }
    }
    return false;
  }
}
```

```java
package io.namei.agent.adapter.springai;

import io.namei.agent.kernel.port.ChatModelPort;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpringAiAdapterConfiguration {
  @Bean
  ChatModelPort chatModelPort(ChatModel chatModel) {
    return new SpringAiChatModelAdapter(chatModel);
  }
}
```

- [ ] **步骤 4：运行测试并检查依赖方向**

运行：

```bash
./mvnw -pl adapter-spring-ai -am test
./mvnw -pl agent-kernel dependency:tree
```

预期：适配器测试通过；`agent-kernel` 依赖树中不出现 Spring 或 Spring AI。

- [ ] **步骤 5：提交**

```bash
git add agent-kernel/src/main/java/io/namei/agent/kernel/error adapter-spring-ai
git commit -m "feat: 增加 Spring AI 模型适配器"
```

---

### 任务 8：装配 Spring Boot、配置和 System Prompt

**文件：**

- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/NameiAgentApplication.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/config/AgentProperties.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/config/ProviderConfigurationGuard.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/config/ApplicationConfiguration.java`
- 创建：`agent-bootstrap/src/main/resources/application.yml`
- 创建：`agent-bootstrap/src/main/resources/prompts/system.md`
- 创建：`.env.example`
- 创建：`agent-bootstrap/src/test/java/io/namei/agent/bootstrap/config/ApplicationConfigurationTest.java`

**接口：**

- 配置：`agent.workspace`、`agent.history.max-messages`、`agent.history.max-characters`、`agent.model.timeout`
- 环境变量：`AKASHIC_WORKSPACE`、`OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL`
- 产出 Bean：`SqliteSchemaInitializer`、`JdbcSessionRepository`、`SessionRepository`、`SessionExecutionGate`、`ChatUseCase`。

- [ ] **步骤 1：编写缺少 Provider 配置时启动失败测试**

```java
package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ApplicationConfigurationTest {
  @Test
  void rejectsMissingProviderSettingsWithoutLeakingApiKey() {
    var environment = new MockEnvironment()
        .withProperty("spring.ai.openai.base-url", "")
        .withProperty("spring.ai.openai.api-key", "")
        .withProperty("spring.ai.openai.chat.model", "");

    assertThatThrownBy(() -> new ProviderConfigurationGuard(environment).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("缺少必需模型配置: base-url, api-key, model")
        .hasMessageNotContaining("Bearer");
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl agent-bootstrap -am test -Dtest=ApplicationConfigurationTest`

预期：编译失败，提示缺少配置类。

- [ ] **步骤 3：实现配置绑定、启动校验和依赖装配**

```java
package io.namei.agent.bootstrap.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentProperties(Path workspace, History history, Model model) {
  public AgentProperties {
    if (workspace == null) throw new IllegalArgumentException("agent.workspace 必填");
    if (history == null) history = new History(40, 100_000);
    if (model == null) model = new Model(Duration.ofSeconds(60));
  }
  public record History(int maxMessages, int maxCharacters) {}
  public record Model(Duration timeout) {}
}
```

```java
package io.namei.agent.bootstrap.config;

import java.util.ArrayList;
import org.springframework.core.env.Environment;

public final class ProviderConfigurationGuard {
  private final Environment environment;
  public ProviderConfigurationGuard(Environment environment) { this.environment = environment; }
  public void validate() {
    var missing = new ArrayList<String>();
    require("spring.ai.openai.base-url", "base-url", missing);
    require("spring.ai.openai.api-key", "api-key", missing);
    require("spring.ai.openai.chat.model", "model", missing);
    if (!missing.isEmpty()) throw new IllegalStateException("缺少必需模型配置: " + String.join(", ", missing));
  }
  private void require(String property, String label, java.util.List<String> missing) {
    String value = environment.getProperty(property, "");
    if (value.isBlank()) missing.add(label);
  }
}
```

```java
package io.namei.agent.bootstrap.config;

import io.namei.agent.adapter.sqlite.*;
import io.namei.agent.application.*;
import io.namei.agent.kernel.history.*;
import io.namei.agent.kernel.port.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class ApplicationConfiguration {
  @Bean
  InitializingBean providerConfigurationGuard(Environment environment) {
    return () -> new ProviderConfigurationGuard(environment).validate();
  }

  @Bean
  SqliteSchemaInitializer sqliteSchema(AgentProperties properties) {
    try { Files.createDirectories(properties.workspace()); }
    catch (IOException exception) { throw new IllegalStateException("无法创建工作区", exception); }
    var schema = new SqliteSchemaInitializer(properties.workspace().resolve("sessions.db"), 5_000);
    schema.initialize();
    return schema;
  }

  @Bean
  JdbcSessionRepository jdbcSessionRepository(SqliteSchemaInitializer schema) {
    return new JdbcSessionRepository(schema);
  }

  @Bean
  SessionRepository sessionRepository(JdbcSessionRepository repository) { return repository; }

  @Bean
  SessionExecutionGate sessionExecutionGate(AgentProperties properties) {
    return new KeyedSessionExecutionGate(properties.model().timeout());
  }

  @Bean
  ChatUseCase chatUseCase(SessionRepository sessions, ChatModelPort model,
      SessionExecutionGate gate, AgentProperties properties,
      @Value("classpath:/prompts/system.md") Resource systemPrompt) throws IOException {
    String prompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8).trim();
    return new ChatService(sessions, model, new ConversationHistorySelector(),
        new HistoryLimits(properties.history().maxMessages(), properties.history().maxCharacters()),
        gate, prompt, Clock.systemUTC());
  }
}
```

```java
package io.namei.agent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import io.namei.agent.adapter.springai.SpringAiAdapterConfiguration;
import io.namei.agent.bootstrap.config.ApplicationConfiguration;

@SpringBootApplication
@Import({ApplicationConfiguration.class, SpringAiAdapterConfiguration.class})
public class NameiAgentApplication {
  public static void main(String[] args) {
    SpringApplication.run(NameiAgentApplication.class, args);
  }
}
```

- [ ] **步骤 4：写入运行配置和默认 Prompt**

```yaml
server:
  address: 127.0.0.1
  shutdown: graceful
spring:
  main:
    keep-alive: true
  threads:
    virtual:
      enabled: true
  lifecycle:
    timeout-per-shutdown-phase: 20s
  ai:
    model:
      embedding: none
    openai:
      base-url: ${OPENAI_BASE_URL:}
      api-key: ${OPENAI_API_KEY:}
      chat:
        model: ${OPENAI_MODEL:}
        temperature: 0.7
        timeout: ${AGENT_MODEL_TIMEOUT:60s}
agent:
  workspace: ${AKASHIC_WORKSPACE:./workspace}
  history:
    max-messages: 40
    max-characters: 100000
  model:
    timeout: ${AGENT_MODEL_TIMEOUT:60s}
management:
  endpoints:
    web:
      exposure:
        include: health
    jmx:
      exposure:
        exclude: "*"
  endpoint:
    health:
      show-details: never
```

`prompts/system.md`：

```markdown
你是 Namei Agent。请根据给定的对话历史直接、准确地回答当前用户消息。
```

`.env.example`：

```dotenv
AKASHIC_WORKSPACE=./workspace
OPENAI_BASE_URL=https://api.openai.com
OPENAI_API_KEY=replace-me
OPENAI_MODEL=gpt-4o-mini
```

- [ ] **步骤 5：运行配置测试和离线 Context Smoke Test**

运行：`./mvnw -pl agent-bootstrap -am test -Dtest=ApplicationConfigurationTest`

预期：测试通过；测试输出和 Surefire 报告中不出现 API Key 值。

- [ ] **步骤 6：提交**

```bash
git add .env.example agent-bootstrap/src
git commit -m "feat: 装配被动聊天 Spring Boot 应用"
```

---

### 任务 9：实现 HTTP 契约、请求 ID 与错误映射

**文件：**

- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/http/ChatRequest.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/http/ChatResponse.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/http/ChatController.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/http/RequestIdFilter.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/http/ContentLengthLimitFilter.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/http/ApiExceptionHandler.java`
- 创建：`agent-bootstrap/src/test/java/io/namei/agent/bootstrap/http/ChatControllerTest.java`

**接口：**

- 产出：`POST /api/v1/chat`
- 请求：`{"sessionId":"demo","message":"你好"}`
- 成功：`{"sessionId":"demo","message":{"role":"assistant","content":"..."}}`
- 错误：RFC 9457 `ProblemDetail`；所有响应带 `X-Request-Id`。

- [ ] **步骤 1：编写成功、校验和 502 映射失败测试**

```java
package io.namei.agent.bootstrap.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ChatController.class, ApiExceptionHandler.class, RequestIdFilter.class,
    ContentLengthLimitFilter.class})
class ChatControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean ChatUseCase useCase;

  @Test
  void returnsAssistantMessageAndRequestId() throws Exception {
    when(useCase.chat(any())).thenReturn(new ChatResult("demo",
        new ChatMessage(MessageRole.ASSISTANT, "回答")));
    mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", "request-1")
            .content("""{"sessionId":"demo","message":"问题"}"""))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", "request-1"))
        .andExpect(jsonPath("$.message.role").value("assistant"))
        .andExpect(jsonPath("$.message.content").value("回答"));
  }

  @Test
  void rejectsInvalidSessionId() throws Exception {
    mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
            .content("""{"sessionId":"../bad","message":"问题"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void mapsProviderFailureToBadGateway() throws Exception {
    when(useCase.chat(any())).thenThrow(new ModelInvocationException("模型调用失败", new RuntimeException()));
    mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
            .content("""{"sessionId":"demo","message":"问题"}"""))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("模型调用失败"));
  }

  @Test
  void rejectsOversizedRequestBody() throws Exception {
    mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
            .header("Content-Length", "70000").content("{}"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(header().exists("X-Request-Id"));
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl agent-bootstrap -am test -Dtest=ChatControllerTest`

预期：编译失败，提示缺少 HTTP 类型。

- [ ] **步骤 3：实现 DTO 和 Controller**

```java
package io.namei.agent.bootstrap.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_-]+") String sessionId,
    @NotBlank @Size(max = 32_000) String message) {}
```

```java
package io.namei.agent.bootstrap.http;

public record ChatResponse(String sessionId, Message message) {
  public record Message(String role, String content) {}
}
```

```java
package io.namei.agent.bootstrap.http;

import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
  private final ChatUseCase chat;
  public ChatController(ChatUseCase chat) { this.chat = chat; }
  @PostMapping
  ChatResponse chat(@Valid @RequestBody ChatRequest request) {
    var result = chat.chat(new ChatCommand(request.sessionId(), request.message().trim()));
    return new ChatResponse(result.sessionId(),
        new ChatResponse.Message("assistant", result.assistant().content()));
  }
}
```

- [ ] **步骤 4：实现请求 ID Filter 和错误映射**

```java
package io.namei.agent.bootstrap.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Request-Id";
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
  @Override protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String requestId = incoming != null && VALID.matcher(incoming).matches()
        ? incoming : UUID.randomUUID().toString();
    response.setHeader(HEADER, requestId);
    try (var ignored = MDC.putCloseable("requestId", requestId)) { chain.doFilter(request, response); }
  }
}
```

```java
package io.namei.agent.bootstrap.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ContentLengthLimitFilter extends OncePerRequestFilter {
  private static final long MAX_BYTES = 65_536;
  @Override protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    long length = request.getContentLengthLong();
    if (length > MAX_BYTES) {
      response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return;
    }
    chain.doFilter(request, response);
  }
}
```

```java
package io.namei.agent.bootstrap.http;

import io.namei.agent.adapter.sqlite.SqliteRepositoryException;
import io.namei.agent.application.SessionLockTimeoutException;
import io.namei.agent.kernel.error.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "请求参数无效", request);
  }
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail malformedJson(HttpMessageNotReadableException exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "JSON 格式无效", request);
  }
  @ExceptionHandler({ModelInvocationException.class, InvalidModelResponseException.class})
  ProblemDetail modelFailure(RuntimeException exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_GATEWAY, "模型调用失败", request);
  }
  @ExceptionHandler({ModelTimeoutException.class, SessionLockTimeoutException.class})
  ProblemDetail timeout(RuntimeException exception, HttpServletRequest request) {
    return problem(HttpStatus.GATEWAY_TIMEOUT, "请求执行超时", request);
  }
  @ExceptionHandler(SqliteRepositoryException.class)
  ProblemDetail persistence(SqliteRepositoryException exception, HttpServletRequest request) {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "会话持久化失败", request);
  }
  private ProblemDetail problem(HttpStatus status, String title, HttpServletRequest request) {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, title);
    detail.setTitle(title);
    detail.setInstance(java.net.URI.create(request.getRequestURI()));
    return detail;
  }
}
```

- [ ] **步骤 5：运行目标测试**

运行：`./mvnw -pl agent-bootstrap -am test -Dtest=ChatControllerTest`

预期：4 个测试通过；错误响应不含异常类名或堆栈；超限请求返回 413。

- [ ] **步骤 6：提交**

```bash
git add agent-bootstrap/src/main/java/io/namei/agent/bootstrap/http agent-bootstrap/src/test/java/io/namei/agent/bootstrap/http
git commit -m "feat: 提供被动聊天 HTTP API"
```

---

### 任务 10：使用本地 OpenAI-compatible 桩服务验证 Spring AI

**文件：**

- 修改：`adapter-spring-ai/pom.xml`
- 创建：`adapter-spring-ai/src/test/java/io/namei/agent/adapter/springai/OpenAiStubServer.java`
- 创建：`adapter-spring-ai/src/test/java/io/namei/agent/adapter/springai/OpenAiCompatibleAdapterIT.java`

**接口：**

- 验证真实 Spring AI Auto-configuration 与 `ChatModelPort` Adapter 的组合。
- 网络只允许访问测试进程创建的 `127.0.0.1` 桩服务。
- 覆盖成功、401、429、500、非法 JSON、空 Choices 和超时。

- [ ] **步骤 1：加入仅测试使用的 OpenAI Starter**

在 `adapter-spring-ai/pom.xml` 中加入：

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-openai</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **步骤 2：创建无第三方依赖的本地 HTTP 桩服务**

```java
package io.namei.agent.adapter.springai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OpenAiStubServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private volatile Response response = new Response(200, successBody("回答"), Duration.ZERO);

  OpenAiStubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", this::handle);
    server.setExecutor(executor);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void respond(int status, String body) {
    response = new Response(status, body, Duration.ZERO);
  }

  void respondAfter(Duration delay, String body) {
    response = new Response(200, body, delay);
  }

  static String successBody(String content) {
    return """
        {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.formatted(content);
  }

  private void handle(HttpExchange exchange) throws IOException {
    Response selected = response;
    try {
      Thread.sleep(selected.delay());
      byte[] body = selected.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(selected.status(), body.length);
      exchange.getResponseBody().write(body);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  @Override public void close() { server.stop(0); executor.close(); }
  private record Response(int status, String body, Duration delay) {}
}
```

- [ ] **步骤 3：编写 Spring Context 集成失败测试**

```java
package io.namei.agent.adapter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.*;
import io.namei.agent.kernel.port.ChatModelPort;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = OpenAiCompatibleAdapterIT.TestApplication.class)
class OpenAiCompatibleAdapterIT {
  private static final OpenAiStubServer server = createServer();
  @Autowired ChatModelPort model;

  @AfterAll static void stopServer() { server.close(); }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.base-url", server::baseUrl);
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("spring.ai.openai.chat.model", () -> "test-model");
    registry.add("spring.ai.openai.chat.timeout", () -> "50ms");
    registry.add("spring.ai.model.embedding", () -> "none");
  }

  @Test
  void callsLocalOpenAiCompatibleEndpoint() {
    server.respond(200, OpenAiStubServer.successBody("回答"));
    assertThat(model.generate(request()).content()).isEqualTo("回答");
  }

  @ParameterizedTest
  @MethodSource("upstreamFailures")
  void mapsUpstreamStatusToStableException(int status) {
    server.respond(status, "{\"error\":{\"message\":\"failed\"}}");
    assertThatThrownBy(() -> model.generate(request()))
        .isInstanceOf(ModelInvocationException.class)
        .hasMessage("模型调用失败");
  }

  @Test
  void rejectsInvalidOrEmptyResponse() {
    server.respond(200, "not-json");
    assertThatThrownBy(() -> model.generate(request())).isInstanceOf(ModelInvocationException.class);
    server.respond(200, "{\"choices\":[]}");
    assertThatThrownBy(() -> model.generate(request()))
        .isInstanceOf(InvalidModelResponseException.class);
  }

  @Test
  void mapsTimeout() {
    server.respondAfter(Duration.ofMillis(250), OpenAiStubServer.successBody("迟到"));
    assertThatThrownBy(() -> model.generate(request())).isInstanceOf(ModelTimeoutException.class);
  }

  private static Stream<Integer> upstreamFailures() { return Stream.of(401, 429, 500); }
  private static OpenAiStubServer createServer() {
    try { return new OpenAiStubServer(); }
    catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
  }
  private static ChatModelRequest request() {
    return new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "问题")));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(SpringAiAdapterConfiguration.class)
  static class TestApplication {}
}
```

- [ ] **步骤 4：运行测试并确认首次失败原因**

运行：`./mvnw -pl adapter-spring-ai -am verify -Dit.test=OpenAiCompatibleAdapterIT`

预期：成功响应测试通过；401、429、500 和非法 JSON 映射为 `ModelInvocationException`；空 Choices 映射为 `InvalidModelResponseException`；50ms 超时映射为 `ModelTimeoutException`。

- [ ] **步骤 5：修正 Adapter 后重新验证全部场景**

运行：`./mvnw -pl adapter-spring-ai -am verify`

预期：成功、401、429、500、非法 JSON、空 Choices 和超时场景全部通过；测试日志中的目标 URL 只能是 `127.0.0.1`。

- [ ] **步骤 6：提交**

```bash
git add adapter-spring-ai
git commit -m "test: 验证 OpenAI-compatible 模型协议"
```

---

### 任务 11：强制安全运行边界、健康检查和架构边界

**文件：**

- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/health/SqliteHealthIndicator.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/observability/SafeChatUseCase.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/observability/ObservedChatModelPort.java`
- 创建：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/observability/ObservedSessionRepository.java`
- 修改：`agent-bootstrap/src/main/java/io/namei/agent/bootstrap/config/ApplicationConfiguration.java`
- 创建：`agent-bootstrap/src/test/java/io/namei/agent/bootstrap/architecture/ArchitectureTest.java`
- 创建：`agent-bootstrap/src/test/java/io/namei/agent/bootstrap/health/ActuatorExposureIT.java`
- 创建：`agent-bootstrap/src/test/java/io/namei/agent/bootstrap/observability/SafeChatUseCaseTest.java`
- 创建：`agent-bootstrap/src/test/java/io/namei/agent/bootstrap/observability/ObservedPortsTest.java`

**接口：**

- 产出：只记录 `requestId`、`sessionIdHash`、耗时、结果和错误码的 `SafeChatUseCase` Decorator。
- 产出：不调用模型的 SQLite Health Indicator。
- 强制：Kernel 不得依赖框架；Actuator 只暴露 Health。

- [ ] **步骤 1：编写日志脱敏和架构失败测试**

```java
package io.namei.agent.bootstrap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.namei.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule kernel_has_no_framework_dependencies = noClasses()
      .that().resideInAPackage("io.namei.agent.kernel..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.springframework..", "java.sql..", "reactor..", "com.openai..");

  @ArchTest
  static final ArchRule application_depends_only_on_jdk_and_kernel = noClasses()
      .that().resideInAPackage("io.namei.agent.application..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.springframework..", "java.sql..", "reactor..", "com.openai..");
}
```

```java
package io.namei.agent.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SafeChatUseCaseTest {
  @Test
  void neverLogsMessageOrUpstreamSecret() {
    Logger logger = (Logger) LoggerFactory.getLogger(SafeChatUseCase.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      ChatUseCase failing = command -> { throw new IllegalStateException("Bearer secret-key"); };
      var observed = new SafeChatUseCase(failing,
          Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC));

      assertThatThrownBy(() -> observed.chat(new ChatCommand("private-session", "TOP-SECRET-CONTENT")))
          .isInstanceOf(IllegalStateException.class);

      String rendered = appender.list.stream()
          .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
          .reduce("", String::concat);
      assertThat(rendered)
          .contains("sessionIdHash", "outcome=\"failure\"")
          .doesNotContain("private-session", "TOP-SECRET-CONTENT", "secret-key", "Bearer");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
```

- [ ] **步骤 2：运行测试并确认失败**

运行：`./mvnw -pl agent-bootstrap -am test -Dtest=ArchitectureTest,SafeChatUseCaseTest`

预期：编译失败，提示缺少 `SafeChatUseCase`。若同时出现真实架构违规，必须修正依赖，禁止删除 ArchUnit 规则。

- [ ] **步骤 3：实现安全用例 Decorator**

```java
package io.namei.agent.bootstrap.observability;

import io.namei.agent.application.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SafeChatUseCase implements ChatUseCase {
  private static final Logger logger = LoggerFactory.getLogger(SafeChatUseCase.class);
  private final ChatUseCase delegate;
  private final Clock clock;
  public SafeChatUseCase(ChatUseCase delegate, Clock clock) { this.delegate = delegate; this.clock = clock; }

  @Override
  public ChatResult chat(ChatCommand command) {
    Instant started = clock.instant();
    String sessionHash = hash(command.sessionId());
    try {
      ChatResult result = delegate.chat(command);
      log("success", "none", sessionHash, started);
      return result;
    } catch (RuntimeException exception) {
      log("failure", exception.getClass().getSimpleName(), sessionHash, started);
      throw exception;
    }
  }

  private void log(String outcome, String errorCode, String sessionHash, Instant started) {
    logger.atInfo()
        .addKeyValue("sessionIdHash", sessionHash)
        .addKeyValue("totalLatencyMs", Duration.between(started, clock.instant()).toMillis())
        .addKeyValue("outcome", outcome)
        .addKeyValue("errorCode", errorCode)
        .log("chat request completed");
  }

  private static String hash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 12);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
```

- [ ] **步骤 4：实现 SQLite Health Indicator 并装配 Decorator**

```java
package io.namei.agent.bootstrap.health;

import io.namei.agent.adapter.sqlite.JdbcSessionRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class SqliteHealthIndicator implements HealthIndicator {
  private final JdbcSessionRepository repository;
  public SqliteHealthIndicator(JdbcSessionRepository repository) { this.repository = repository; }
  @Override public Health health() {
    return repository.isAvailable() ? Health.up().build() : Health.down().build();
  }
}
```

新增两个只记录安全字段的 Port Decorator：

```java
package io.namei.agent.bootstrap.observability;

import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ObservedChatModelPort implements ChatModelPort {
  private static final Logger logger = LoggerFactory.getLogger(ObservedChatModelPort.class);
  private final ChatModelPort delegate;
  private final String modelName;
  public ObservedChatModelPort(ChatModelPort delegate, String modelName) {
    this.delegate = delegate;
    this.modelName = modelName;
  }
  @Override public ChatModelResponse generate(ChatModelRequest request) {
    long startedNanos = System.nanoTime();
    RuntimeException failure = null;
    try {
      return delegate.generate(request);
    } catch (RuntimeException exception) {
      failure = exception;
      throw exception;
    } finally {
      logger.atInfo()
          .addKeyValue("model", modelName)
          .addKeyValue("historyMessageCount", request.messages().size())
          .addKeyValue("modelLatencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))
          .addKeyValue("outcome", failure == null ? "success" : "failure")
          .addKeyValue("errorCode", failure == null ? "none" : failure.getClass().getSimpleName())
          .log("model request completed");
    }
  }
}
```

```java
package io.namei.agent.bootstrap.observability;

import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.SessionRepository;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ObservedSessionRepository implements SessionRepository {
  private static final Logger logger = LoggerFactory.getLogger(ObservedSessionRepository.class);
  private final SessionRepository delegate;
  public ObservedSessionRepository(SessionRepository delegate) { this.delegate = delegate; }
  @Override public SessionSnapshot load(String sessionId) {
    return observe(() -> delegate.load(sessionId));
  }
  @Override public void appendTurn(String sessionId, PersistedTurn turn) {
    observe(() -> { delegate.appendTurn(sessionId, turn); return null; });
  }
  private <T> T observe(Supplier<T> action) {
    long startedNanos = System.nanoTime();
    RuntimeException failure = null;
    try {
      return action.get();
    } catch (RuntimeException exception) {
      failure = exception;
      throw exception;
    } finally {
      logger.atInfo()
          .addKeyValue("databaseLatencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))
          .addKeyValue("outcome", failure == null ? "success" : "failure")
          .addKeyValue("errorCode", failure == null ? "none" : failure.getClass().getSimpleName())
          .log("session database operation completed");
    }
  }
}
```

把 `ApplicationConfiguration.chatUseCase` 替换为以下装配，并注册 Health Indicator：

```java
@Bean
ChatUseCase chatUseCase(JdbcSessionRepository sessions, ChatModelPort model,
    SessionExecutionGate gate, AgentProperties properties,
    @Value("${spring.ai.openai.chat.model}") String modelName,
    @Value("classpath:/prompts/system.md") Resource systemPrompt) throws IOException {
  String prompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8).trim();
  var service = new ChatService(
      new ObservedSessionRepository(sessions), new ObservedChatModelPort(model, modelName),
      new ConversationHistorySelector(),
      new HistoryLimits(properties.history().maxMessages(), properties.history().maxCharacters()),
      gate, prompt, Clock.systemUTC());
  return new SafeChatUseCase(service, Clock.systemUTC());
}

@Bean
SqliteHealthIndicator sqliteHealthIndicator(JdbcSessionRepository repository) {
  return new SqliteHealthIndicator(repository);
}
```

为两个 Port Decorator 添加完整脱敏测试：

```java
package io.namei.agent.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.namei.agent.kernel.model.*;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.SessionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ObservedPortsTest {
  @Test
  void modelLogDoesNotContainPromptOrUpstreamMessage() {
    ChatModelPort failing = request -> { throw new IllegalStateException("Bearer model-secret"); };
    String log = capture(ObservedChatModelPort.class, () ->
        new ObservedChatModelPort(failing, "test-model").generate(
            new ChatModelRequest(List.of(new ChatMessage(MessageRole.USER, "TOP-SECRET-MODEL")))));
    assertThat(log).contains("modelLatencyMs", "historyMessageCount")
        .doesNotContain("TOP-SECRET-MODEL", "model-secret", "Bearer");
  }

  @Test
  void databaseLogDoesNotContainSessionOrUpstreamMessage() {
    SessionRepository failing = new SessionRepository() {
      @Override public SessionSnapshot load(String sessionId) {
        throw new IllegalStateException("Bearer database-secret");
      }
      @Override public void appendTurn(String sessionId, PersistedTurn turn) {
        throw new IllegalStateException("Bearer database-secret");
      }
    };
    String log = capture(ObservedSessionRepository.class, () ->
        new ObservedSessionRepository(failing).load("private-database-session"));
    assertThat(log).contains("databaseLatencyMs")
        .doesNotContain("private-database-session", "database-secret", "Bearer");
  }

  private static String capture(Class<?> loggerType, Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerType);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class);
      return appender.list.stream()
          .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
          .reduce("", String::concat);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
```

- [ ] **步骤 5：验证只开放 Health**

`ActuatorExposureIT` 使用真实随机端口和 Fake `ChatModelPort`：

```java
package io.namei.agent.bootstrap.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ActuatorExposureIT.FakeModelConfiguration.class)
class ActuatorExposureIT {
  private static final Path workspace = createWorkspace();
  @LocalServerPort int port;
  @org.springframework.beans.factory.annotation.Autowired CountingModel model;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("agent.workspace", workspace::toString);
    registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:1");
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("spring.ai.openai.chat.model", () -> "test-model");
  }

  @AfterAll static void cleanUp() { FileSystemUtils.deleteRecursively(workspace.toFile()); }

  @Test
  void exposesOnlyHealthWithoutCallingModel() {
    RestClient client = RestClient.create("http://127.0.0.1:" + port);
    assertThat(client.get().uri("/actuator/health").retrieve()
        .toEntity(String.class).getStatusCode().value()).isEqualTo(200);
    assertThatThrownBy(() -> client.get().uri("/actuator/env").retrieve().toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThat(model.calls.get()).isZero();
  }

  private static Path createWorkspace() {
    try { return Files.createTempDirectory("namei-health-"); }
    catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
  }

  @TestConfiguration
  static class FakeModelConfiguration {
    @Bean @Primary CountingModel countingModel() { return new CountingModel(); }
  }

  static final class CountingModel implements ChatModelPort {
    private final AtomicInteger calls = new AtomicInteger();
    @Override public ChatModelResponse generate(io.namei.agent.kernel.model.ChatModelRequest request) {
      calls.incrementAndGet();
      return new ChatModelResponse("unused");
    }
  }
}
```

运行：`./mvnw -pl agent-bootstrap -am verify -Dit.test=ActuatorExposureIT`

预期：Health 为 200，`/actuator/env` 为 404，健康检查期间 Fake Model 调用次数为 0。

- [ ] **步骤 6：提交**

```bash
git add agent-bootstrap/src
git commit -m "feat: 强制本地安全与可观测性边界"
```

---

### 任务 12：加入 Python Schema 兼容测试和端到端验收

**文件：**

- 修改：`pom.xml`
- 创建：`adapter-sqlite/src/test/resources/python-session-schema.sql`
- 创建：`adapter-sqlite/src/test/java/io/namei/agent/adapter/sqlite/PythonSchemaCompatibilityIT.java`
- 创建：`agent-bootstrap/src/test/java/io/namei/agent/bootstrap/PassiveChatEndpointIT.java`
- 创建：`agent-bootstrap/src/test/java/io/namei/agent/bootstrap/RealModelSmokeIT.java`

**接口：**

- `./mvnw clean verify`：运行默认离线单元测试和 Integration Test，排除 `compat`、`real-model` Tag。
- `./mvnw -Pcompat verify`：额外运行 Python Schema 兼容测试。
- `./mvnw -Preal-model-smoke verify`：额外运行真实模型 Smoke Test，并要求三个 OpenAI 环境变量存在。

- [ ] **步骤 1：配置 Failsafe Tag Profile**

在父 POM 的 Properties 中加入：

```xml
<excluded.test.groups>compat,real-model</excluded.test.groups>
```

在 `maven-failsafe-plugin` 的 Configuration 中加入：

```xml
<excludedGroups>${excluded.test.groups}</excludedGroups>
```

在父 POM 末尾加入：

```xml
<profiles>
  <profile>
    <id>compat</id>
    <properties><excluded.test.groups>real-model</excluded.test.groups></properties>
  </profile>
  <profile>
    <id>real-model-smoke</id>
    <properties><excluded.test.groups>compat</excluded.test.groups></properties>
  </profile>
</profiles>
```

- [ ] **步骤 2：创建由 Python 当前 Schema 固定的测试数据**

`python-session-schema.sql`：

```sql
CREATE TABLE sessions (
  key TEXT PRIMARY KEY,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  last_consolidated INTEGER NOT NULL DEFAULT 0,
  metadata TEXT,
  last_user_at TEXT,
  last_proactive_at TEXT,
  next_seq INTEGER NOT NULL DEFAULT 0,
  future_column TEXT
);
CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  session_key TEXT NOT NULL,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT,
  tool_chain TEXT,
  extra TEXT,
  ts TEXT NOT NULL,
  future_column TEXT,
  UNIQUE(session_key, seq)
);
INSERT INTO sessions VALUES (
  'python-demo', '2026-07-12T00:00:00+08:00', '2026-07-12T00:00:01+08:00',
  0, '{"unknown":"keep"}', '2026-07-12T00:00:00+08:00', NULL, 2, 'keep-session');
INSERT INTO messages VALUES (
  'python-demo:0', 'python-demo', 0, 'user', 'Python 问题', NULL,
  '{"unknown":"keep"}', '2026-07-12T00:00:00+08:00', 'keep-message-0');
INSERT INTO messages VALUES (
  'python-demo:1', 'python-demo', 1, 'assistant', 'Python 回答', NULL,
  '{"unknown":"keep"}', '2026-07-12T00:00:01+08:00', 'keep-message-1');
```

- [ ] **步骤 3：编写兼容性失败测试**

```java
package io.namei.agent.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("compat")
class PythonSchemaCompatibilityIT {
  @TempDir Path tempDir;

  @Test
  void readsPythonRowsAndPreservesUnknownDataWhenAppending() throws Exception {
    Path database = tempDir.resolve("sessions.db");
    var schema = new SqliteSchemaInitializer(database, 5_000);
    String sql = new String(getClass().getResourceAsStream("/python-session-schema.sql").readAllBytes(),
        StandardCharsets.UTF_8);
    try (var connection = schema.openConnection(); var statement = connection.createStatement()) {
      for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command);
    }
    schema.initialize();
    var repository = new JdbcSessionRepository(schema);

    assertThat(repository.load("python-demo").messages()).containsExactly(
        new ChatMessage(MessageRole.USER, "Python 问题"),
        new ChatMessage(MessageRole.ASSISTANT, "Python 回答"));

    OffsetDateTime now = OffsetDateTime.parse("2026-07-13T08:00:00+08:00");
    repository.appendTurn("python-demo", new PersistedTurn(
        new ChatMessage(MessageRole.USER, "Java 问题"), now,
        new ChatMessage(MessageRole.ASSISTANT, "Java 回答"), now.plusSeconds(1)));

    try (var connection = schema.openConnection();
         var session = connection.createStatement().executeQuery(
             "SELECT metadata, future_column FROM sessions WHERE key='python-demo'")) {
      assertThat(session.next()).isTrue();
      assertThat(session.getString("metadata")).isEqualTo("{\"unknown\":\"keep\"}");
      assertThat(session.getString("future_column")).isEqualTo("keep-session");
    }
  }
}
```

- [ ] **步骤 4：运行默认构建并确认兼容测试未执行**

运行：`./mvnw clean verify`

预期：成功；Failsafe 报告中没有 `PythonSchemaCompatibilityIT`。

- [ ] **步骤 5：运行兼容 Profile 并确认通过**

运行：`./mvnw -Pcompat verify`

预期：`PythonSchemaCompatibilityIT` 执行且通过，原有未知字段保持不变。

- [ ] **步骤 6：编写 HTTP 到 SQLite 的端到端测试**

```java
package io.namei.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.model.*;
import io.namei.agent.kernel.port.ChatModelPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PassiveChatEndpointIT.FakeModelConfiguration.class)
class PassiveChatEndpointIT {
  private static final Path workspace = createWorkspace();
  @LocalServerPort int port;
  @org.springframework.beans.factory.annotation.Autowired RecordingModel model;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("agent.workspace", workspace::toString);
    registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:1");
    registry.add("spring.ai.openai.api-key", () -> "test-key");
    registry.add("spring.ai.openai.chat.model", () -> "test-model");
  }

  @AfterAll static void cleanUp() { FileSystemUtils.deleteRecursively(workspace.toFile()); }

  @Test
  void completesTwoTurnsAndSuppliesFirstTurnAsHistory() throws Exception {
    RestClient client = RestClient.create("http://127.0.0.1:" + port);
    post(client, "第一问");
    post(client, "第二问");
    assertThat(model.requests).hasSize(2);
    assertThat(model.requests.get(1).messages()).extracting(ChatMessage::content)
        .containsSubsequence("第一问", "第一答", "第二问");
    try (var connection = DriverManager.getConnection(
        "jdbc:sqlite:" + workspace.resolve("sessions.db").toAbsolutePath());
         var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM messages")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(4);
    }
  }

  private static void post(RestClient client, String message) {
    String body = "{\"sessionId\":\"demo\",\"message\":\"" + message + "\"}";
    var response = client.post().uri("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
        .body(body).retrieve().toEntity(String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  private static Path createWorkspace() {
    try { return Files.createTempDirectory("namei-e2e-"); }
    catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
  }

  @TestConfiguration
  static class FakeModelConfiguration {
    @Bean @Primary RecordingModel recordingModel() { return new RecordingModel(); }
  }

  static final class RecordingModel implements ChatModelPort {
    final List<ChatModelRequest> requests = new CopyOnWriteArrayList<>();
    @Override public ChatModelResponse generate(ChatModelRequest request) {
      requests.add(request);
      String question = request.messages().getLast().content();
      return new ChatModelResponse(question.equals("第一问") ? "第一答" : "第二答");
    }
  }
}
```

运行：`./mvnw -pl agent-bootstrap -am verify -Dit.test=PassiveChatEndpointIT`

预期：测试通过；临时 `sessions.db` 中有 4 条消息，第二次模型请求包含第一轮历史。

- [ ] **步骤 7：加入真实模型 Smoke Test**

```java
package io.namei.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.RestClient;

@Tag("real-model")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealModelSmokeIT {
  private static final Path workspace = createWorkspace();
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("agent.workspace", workspace::toString);
    registry.add("spring.ai.openai.base-url", () -> required("OPENAI_BASE_URL"));
    registry.add("spring.ai.openai.api-key", () -> required("OPENAI_API_KEY"));
    registry.add("spring.ai.openai.chat.model", () -> required("OPENAI_MODEL"));
  }

  @AfterAll static void cleanUp() { FileSystemUtils.deleteRecursively(workspace.toFile()); }

  @Test
  void returnsOneNonEmptyAssistantMessage() {
    String body = RestClient.create("http://127.0.0.1:" + port).post()
        .uri("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
        .body("{\"sessionId\":\"real-smoke\",\"message\":\"只回复 pong\"}")
        .retrieve().body(String.class);
    assertThat(body)
        .contains("\"role\":\"assistant\"", "\"content\":")
        .doesNotContain("\"content\":\"\"");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量: " + name);
    return value;
  }

  private static Path createWorkspace() {
    try { return Files.createTempDirectory("namei-real-smoke-"); }
    catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
  }
}
```

测试不得打印环境变量值。

运行：`./mvnw -Preal-model-smoke verify`

预期：未设置环境变量时测试明确失败并只显示缺失变量名；设置后调用一次真实模型并通过。

- [ ] **步骤 8：提交**

```bash
git add pom.xml adapter-sqlite/src/test agent-bootstrap/src/test
git commit -m "test: 增加兼容性与端到端验收"
```

---

### 任务 13：补全运行文档并执行最终验证

**文件：**

- 创建：`README.md`
- 创建：`docs/runbooks/local-development.md`
- 创建：`docs/contracts/passive-chat-http.md`
- 修改：`docs/superpowers/specs/2026-07-12-passive-chat-mvp-design.md`

**接口：**

- 文档必须说明 JDK 21、环境变量、构建、启动、Curl、SQLite 安全和故障排查。
- Spec 状态更新为“已实现”之前，必须具有完整测试和 Review 证据。

- [ ] **步骤 1：编写 README 和运行手册**

README 必须包含以下可直接复制的命令：

```bash
cp .env.example .env
set -a && source .env && set +a
./mvnw clean verify
./mvnw -pl agent-bootstrap -am spring-boot:run
```

HTTP 示例：

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: local-demo-1' \
  -d '{"sessionId":"demo","message":"你好"}' \
  http://127.0.0.1:8080/api/v1/chat
```

运行手册必须明确：禁止 Python 和 Java 同时写同一 Workspace；真实 Python Workspace 在本里程碑只读；首次写入前备份 `sessions.db`、`sessions.db-wal` 和 `sessions.db-shm`。

- [ ] **步骤 2：编写 HTTP Contract 文档**

`docs/contracts/passive-chat-http.md` 必须逐字记录已批准的 Request、Success Response、400/500/502/504 `ProblemDetail`、`X-Request-Id` 和字段长度规则，并链接到设计 Spec。不得新增 Streaming、Tool 或 Provider Override 字段。

- [ ] **步骤 3：运行格式化和目标测试**

```bash
./mvnw spotless:apply
./mvnw -pl agent-kernel,agent-application -am test
./mvnw -pl adapter-sqlite -am test
./mvnw -pl adapter-spring-ai -am verify
./mvnw -pl agent-bootstrap -am verify
```

预期：每条命令退出码为 0；无测试失败或错误。

- [ ] **步骤 4：运行完整兼容性验证**

```bash
./mvnw clean verify
./mvnw -Pcompat verify
```

预期：两条命令退出码为 0；默认构建不访问真实模型；兼容 Profile 明确执行 `PythonSchemaCompatibilityIT`。

- [ ] **步骤 5：检查依赖、Secret 和工作区**

```bash
./mvnw -pl agent-kernel dependency:tree
if ./mvnw dependency:tree | rg -v 'io.namei.agent' | rg 'SNAPSHOT|spring-boot-starter-webflux|spring-data-jpa|hibernate|r2dbc|lombok'; then exit 1; fi
if rg -n 'sk-[A-Za-z0-9_-]{16,}|Bearer [A-Za-z0-9._-]+' . --glob '!target/**' --glob '!.git/**'; then exit 1; fi
git status --short
```

预期：Kernel 依赖树无框架；禁止依赖搜索无输出；Secret 搜索无输出；Git 状态只包含本任务预期文档变更。

- [ ] **步骤 6：请求独立审查**

使用 `requesting-code-review`，要求审查者分别检查：Spec 符合度、架构依赖方向、SQLite 原子性与兼容性、同会话并发语义、日志脱敏和错误状态码。任何 Critical 或 Important 问题必须修复并重新运行步骤 3–5。

- [ ] **步骤 7：更新 Spec 状态并提交**

只有步骤 3–6 全部通过后，才把 Spec 状态从“已批准”改为“已实现并验证”，并记录验证日期和命令。

```bash
git add README.md docs
git commit -m "docs: 补全被动聊天 MVP 运行与契约文档"
```

- [ ] **步骤 8：使用完成分支工作流**

调用 `finishing-a-development-branch`，提供本地合并、Pull Request、保留分支或丢弃四个选项。禁止未经选择直接推送或合并。
