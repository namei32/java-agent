package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.springai.SpringAiEmbeddingAdapter;
import io.namei.agent.adapter.sqlite.JavaMemorySchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcJavaMemoryStore;
import io.namei.agent.adapter.workspace.MarkdownMemoryProfileAdapter;
import io.namei.agent.application.MemoryWriteRequest;
import io.namei.agent.application.SemanticMemoryRetrievalAdapter;
import io.namei.agent.bootstrap.NameiAgentApplication;
import io.namei.agent.bootstrap.http.MemoryManagementApi;
import io.namei.agent.kernel.memory.MemoryDeleteStatus;
import io.namei.agent.kernel.memory.MemoryProfile;
import io.namei.agent.kernel.memory.MemoryRetrievalRequest;
import io.namei.agent.kernel.memory.MemoryRetrievalStatus;
import io.namei.agent.kernel.memory.MemoryRuntimeMode;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.port.EmbeddingPort;
import io.namei.agent.kernel.port.MemoryProfilePort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import io.namei.agent.kernel.port.Tool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

class MemoryConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void appliesDisabledProductionDefaultsAndValidatesEveryJavaNativeSetting() {
    var defaults =
        new AgentProperties(temporaryDirectory.resolve("workspace"), null, null, null, null, null);

    assertThat(defaults.memory().mode()).isEqualTo(MemoryRuntimeMode.DISABLED);
    assertThat(defaults.memory().maxFileBytes()).isEqualTo(65_536);
    assertThat(defaults.memory().maxContextCharacters()).isEqualTo(100_000);
    assertThat(defaults.memory().maxRetrievedCharacters()).isEqualTo(20_000);
    assertThat(defaults.memory().embedding())
        .isEqualTo(new AgentProperties.Embedding("text-embedding-v3", 1024, 2000));
    assertThat(defaults.memory().retrieval())
        .isEqualTo(new AgentProperties.Retrieval(8, 0.45, 0.20, 14.0, 10_000, 6000));

    assertInvalid(() -> new AgentProperties.Embedding(" ", 1024, 2000));
    assertInvalid(() -> new AgentProperties.Embedding("model", 0, 2000));
    assertInvalid(() -> new AgentProperties.Embedding("model", 4097, 2000));
    assertInvalid(() -> new AgentProperties.Embedding("model", 1024, 0));
    assertInvalid(() -> new AgentProperties.Embedding("model", 1024, 2001));
    assertInvalid(() -> new AgentProperties.Retrieval(0, 0.45, 0.2, 14, 100, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(101, 0.45, 0.2, 14, 101, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(8, Double.NaN, 0.2, 14, 100, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(8, 1.01, 0.2, 14, 100, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(8, 0.45, -0.01, 14, 100, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(8, 0.45, 1.01, 14, 100, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(8, 0.45, 0.2, 0, 100, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(8, 0.45, 0.2, 14, 7, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(8, 0.45, 0.2, 14, 10_001, 6000));
    assertInvalid(() -> new AgentProperties.Retrieval(8, 0.45, 0.2, 14, 100, 0));
    assertInvalid(
        () ->
            new AgentProperties.Memory(
                MemoryRuntimeMode.JAVA_NATIVE,
                65_536,
                100_000,
                5000,
                new AgentProperties.Embedding("model", 2, 2000),
                new AgentProperties.Retrieval(8, 0.45, 0.2, 14, 100, 6000)));
  }

  @Test
  void disabledModeReturnsEmptyProfileWithoutTouchingMemoryWorkspace() {
    Path workspace = temporaryDirectory.resolve("must-not-be-read-or-created");
    var properties = properties(workspace, MemoryRuntimeMode.DISABLED);
    var configuration = new ApplicationConfiguration();

    var profiles = configuration.memoryProfilePort(properties);

    assertThat(profiles.load()).isEqualTo(MemoryProfile.empty());
    assertThat(workspace).doesNotExist();
    assertThat(profiles).isNotInstanceOf(MarkdownMemoryProfileAdapter.class);
  }

  @Test
  void readOnlyModeKeepsMarkdownAndDoesNotCreateJavaMemoryDatabase() throws Exception {
    Path workspace = temporaryDirectory.resolve("read-only-workspace");
    Path memory = Files.createDirectories(workspace.resolve("memory"));
    Files.writeString(memory.resolve("SELF.md"), "只读身份");

    runner(workspace, MemoryRuntimeMode.READ_ONLY)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(MemoryProfilePort.class))
                  .isInstanceOf(MarkdownMemoryProfileAdapter.class);
              assertThat(context.getBean(MemoryProfilePort.class).load().selfModel())
                  .isEqualTo("只读身份");
              assertThat(
                      context
                          .getBean(MemoryRetrievalPort.class)
                          .retrieve(request("demo", "问题"))
                          .trace()
                          .status())
                  .isEqualTo(MemoryRetrievalStatus.DISABLED);
              assertThatThrownBy(() -> context.getBean(MemoryManagementApi.class).list("demo"))
                  .isInstanceOf(RuntimeException.class)
                  .hasMessage("记忆功能不可用");
              assertThat(context).doesNotHaveBean(JavaMemorySchemaInitializer.class);
              assertThat(context).doesNotHaveBean(JdbcJavaMemoryStore.class);
              assertThat(context).doesNotHaveBean(EmbeddingPort.class);
            });

    assertThat(workspace.resolve("memory/agent-memory.db")).doesNotExist();
  }

  @Test
  void javaNativeModeWiresSchemaStoreEmbeddingRetrievalAndManagementApi() {
    Path workspace = temporaryDirectory.resolve("java-native-workspace");

    runner(workspace, MemoryRuntimeMode.JAVA_NATIVE)
        .withBean(EmbeddingModel.class, StubEmbeddingModel::new)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JavaMemorySchemaInitializer.class);
              assertThat(context).hasSingleBean(JdbcJavaMemoryStore.class);
              assertThat(context).hasSingleBean(EmbeddingPort.class);
              assertThat(context.getBean(EmbeddingPort.class))
                  .isInstanceOf(SpringAiEmbeddingAdapter.class);
              assertThat(context.getBean(MemoryRetrievalPort.class))
                  .isInstanceOf(SemanticMemoryRetrievalAdapter.class);
              assertThat(context.getBean(MemoryProfilePort.class).load())
                  .isEqualTo(MemoryProfile.empty());

              MemoryManagementApi api = context.getBean(MemoryManagementApi.class);
              var written =
                  api.write(
                      "demo",
                      new MemoryWriteRequest(
                          "request-1", MemoryType.PREFERENCE, "回答时先给结论", 2, null));
              assertThat(api.list("demo")).singleElement();

              var retrieved =
                  context.getBean(MemoryRetrievalPort.class).retrieve(request("demo", "当前问题"));
              assertThat(retrieved.trace().status()).isEqualTo(MemoryRetrievalStatus.RETRIEVED);
              assertThat(retrieved.block()).contains("回答时先给结论");

              assertThat(api.delete("demo", "request-2", written.memory().id()).status())
                  .isEqualTo(MemoryDeleteStatus.DELETED);
              assertThat(api.list("demo")).isEmpty();
              assertNoDeferredMemoryRuntimeBeans(context.getBeanDefinitionNames(), context);
              assertThat(context.getBeansOfType(Tool.class)).isEmpty();
            });

    assertThat(workspace.resolve("memory/agent-memory.db")).isRegularFile();
  }

  @Test
  void rejectsJavaNativeOnNonLoopbackBeforeCreatingItsDatabase() {
    Path workspace = temporaryDirectory.resolve("unsafe-listener-workspace");
    var properties = properties(workspace, MemoryRuntimeMode.JAVA_NATIVE);
    var environment = new MockEnvironment().withProperty("server.address", "0.0.0.0");

    assertThatThrownBy(() -> new JavaNativeMemoryAccessGuard(properties, environment).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("JAVA_NATIVE 记忆只允许 Loopback 监听");
    assertThat(workspace.resolve("memory/agent-memory.db")).doesNotExist();
  }

  @Test
  void javaNativeModeEnablesSpringAiEmbeddingBeforeAutoConfiguration() {
    var disabled = environment(MemoryRuntimeMode.DISABLED);
    var javaNative = environment(MemoryRuntimeMode.JAVA_NATIVE);
    var processor = new JavaNativeMemoryEnvironmentPostProcessor();

    processor.postProcessEnvironment(disabled, new SpringApplication(Object.class));
    processor.postProcessEnvironment(javaNative, new SpringApplication(Object.class));

    assertThat(disabled.getProperty("spring.ai.model.embedding")).isEqualTo("none");
    assertThat(javaNative.getProperty("spring.ai.model.embedding")).isEqualTo("openai");
  }

  @Test
  void productionApplicationBootsJavaNativeWithTheActualSpringAiEmbeddingBean() {
    Path workspace = temporaryDirectory.resolve("production-java-native-workspace");

    try (var context =
        new SpringApplicationBuilder(NameiAgentApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--agent.workspace=" + workspace,
                "--agent.memory.mode=JAVA_NATIVE",
                "--agent.memory.embedding.model=test-embedding",
                "--agent.memory.embedding.dimensions=2",
                "--server.address=127.0.0.1",
                "--spring.ai.openai.base-url=http://127.0.0.1:1/v1",
                "--spring.ai.openai.api-key=test-key",
                "--spring.ai.openai.chat.model=test-chat")) {
      assertThat(context.getEnvironment().getProperty("spring.ai.model.embedding"))
          .isEqualTo("openai");
      assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1);
      assertThat(context.getBean(EmbeddingPort.class)).isInstanceOf(SpringAiEmbeddingAdapter.class);
      assertThat(context.getBean(MemoryRetrievalPort.class))
          .isInstanceOf(SemanticMemoryRetrievalAdapter.class);
      assertNoDeferredMemoryRuntimeBeans(context.getBeanDefinitionNames(), context);
    }

    assertThat(workspace.resolve("memory/agent-memory.db")).isRegularFile();
  }

  @Test
  void templatesKeepMemoryDisabledAndExposeOnlyApprovedJavaNativeSettings() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
    String environmentTemplate = Files.readString(Path.of("../.env.example"));

    assertThat(yaml)
        .contains("mode: ${AGENT_MEMORY_MODE:DISABLED}")
        .contains("max-file-bytes: ${AGENT_MEMORY_MAX_FILE_BYTES:65536}")
        .contains("max-context-characters: ${AGENT_MEMORY_MAX_CONTEXT_CHARACTERS:100000}")
        .contains("max-retrieved-characters: ${AGENT_MEMORY_MAX_RETRIEVED_CHARACTERS:20000}")
        .contains("model: ${AGENT_MEMORY_EMBEDDING_MODEL:text-embedding-v3}")
        .contains("dimensions: ${AGENT_MEMORY_EMBEDDING_DIMENSIONS:1024}")
        .contains("max-text-code-points: ${AGENT_MEMORY_EMBEDDING_MAX_TEXT_CODE_POINTS:2000}")
        .contains("top-k: ${AGENT_MEMORY_RETRIEVAL_TOP_K:8}")
        .contains("score-threshold: ${AGENT_MEMORY_RETRIEVAL_SCORE_THRESHOLD:0.45}")
        .contains("hotness-alpha: ${AGENT_MEMORY_RETRIEVAL_HOTNESS_ALPHA:0.20}")
        .contains("hotness-half-life-days: ${AGENT_MEMORY_RETRIEVAL_HOTNESS_HALF_LIFE_DAYS:14}")
        .contains("max-candidates: ${AGENT_MEMORY_RETRIEVAL_MAX_CANDIDATES:10000}")
        .contains(
            "max-injected-characters: ${AGENT_MEMORY_RETRIEVAL_MAX_INJECTED_CHARACTERS:6000}");
    assertThat(environmentTemplate)
        .contains("AGENT_MEMORY_MODE=DISABLED")
        .contains("AGENT_MEMORY_EMBEDDING_MODEL=text-embedding-v3")
        .contains("AGENT_MEMORY_RETRIEVAL_MAX_INJECTED_CHARACTERS=6000");
  }

  private ApplicationContextRunner runner(Path workspace, MemoryRuntimeMode mode) {
    return new ApplicationContextRunner()
        .withUserConfiguration(ApplicationConfiguration.class)
        .withBean(ChatModel.class, () -> prompt -> null)
        .withPropertyValues(
            "agent.workspace=" + workspace,
            "agent.memory.mode=" + mode,
            "agent.memory.max-file-bytes=65536",
            "agent.memory.max-context-characters=100000",
            "agent.memory.max-retrieved-characters=20000",
            "agent.memory.embedding.model=test-embedding",
            "agent.memory.embedding.dimensions=2",
            "agent.memory.embedding.max-text-code-points=2000",
            "server.address=127.0.0.1",
            "spring.ai.model.embedding=none",
            "spring.ai.openai.base-url=http://127.0.0.1:1/v1",
            "spring.ai.openai.api-key=test-key",
            "spring.ai.openai.chat.model=test-chat");
  }

  private static StandardEnvironment environment(MemoryRuntimeMode mode) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of("agent.memory.mode", mode.name(), "spring.ai.model.embedding", "none")));
    return environment;
  }

  private static MemoryRetrievalRequest request(String sessionId, String message) {
    return new MemoryRetrievalRequest(
        sessionBinding(sessionId), message, List.of(), Instant.parse("2026-07-15T12:00:00Z"));
  }

  private static String sessionBinding(String sessionId) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(sessionId.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void assertNoDeferredMemoryRuntimeBeans(
      String[] beanNames, org.springframework.context.ApplicationContext context) {
    List<String> forbidden = List.of("python", "optimizer", "memorytool", "vectorstore");
    assertThat(Arrays.stream(beanNames).map(context::getType).filter(java.util.Objects::nonNull))
        .noneMatch(
            type -> {
              String normalized = type.getName().toLowerCase(Locale.ROOT).replace("_", "");
              return forbidden.stream().anyMatch(normalized::contains);
            });
    assertThat(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class))
        .isEmpty();
  }

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("agent.memory");
  }

  private static AgentProperties properties(Path workspace, MemoryRuntimeMode mode) {
    return new AgentProperties(
        workspace,
        null,
        null,
        null,
        null,
        new AgentProperties.Memory(mode, 65_536, 100_000, 20_000, null, null));
  }

  private static final class StubEmbeddingModel implements EmbeddingModel {
    @Override
    public EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
      List<Embedding> embeddings =
          IntStream.range(0, request.getInstructions().size())
              .mapToObj(index -> new Embedding(new float[] {1.0f, 0.0f}, index))
              .toList();
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      return new float[] {1.0f, 0.0f};
    }
  }
}
