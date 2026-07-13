package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatModelResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;

class ApplicationConfigurationTest {
  @TempDir Path tempDir;

  @Test
  void rejectsMissingProviderSettingsWithoutLeakingApiKey() {
    var environment =
        new MockEnvironment()
            .withProperty("spring.ai.openai.base-url", "")
            .withProperty("spring.ai.openai.api-key", "")
            .withProperty("spring.ai.openai.chat.model", "");

    assertThatThrownBy(() -> new ProviderConfigurationGuard(environment).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("缺少必需模型配置: base-url, api-key, model")
        .hasMessageNotContaining("Bearer");
  }

  @Test
  void rejectsInvalidProviderBaseUrlWithoutLeakingConfiguredSecret() {
    String secret = "test-secret-must-not-appear";
    var environment =
        new MockEnvironment()
            .withProperty("spring.ai.openai.base-url", "not-a-url")
            .withProperty("spring.ai.openai.api-key", secret)
            .withProperty("spring.ai.openai.chat.model", "test-model");

    assertThatThrownBy(() -> new ProviderConfigurationGuard(environment).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("模型 Base URL 无效")
        .hasMessageNotContaining(secret);
  }

  @Test
  void acceptsCompleteProviderSettings() {
    var environment =
        new MockEnvironment()
            .withProperty("spring.ai.openai.base-url", "http://127.0.0.1:8089")
            .withProperty("spring.ai.openai.api-key", "test-key")
            .withProperty("spring.ai.openai.chat.model", "test-model");

    assertThatCode(() -> new ProviderConfigurationGuard(environment).validate())
        .doesNotThrowAnyException();
  }

  @Test
  void appliesSafeDefaultsAndRejectsInvalidAgentSettings() {
    var defaults = new AgentProperties(tempDir.resolve("workspace"), null, null);

    assertThat(defaults.history().maxMessages()).isEqualTo(40);
    assertThat(defaults.history().maxCharacters()).isEqualTo(100_000);
    assertThat(defaults.model().timeout()).isEqualTo(Duration.ofSeconds(60));
    assertThatThrownBy(() -> new AgentProperties(null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("agent.workspace 必填");
    assertThatThrownBy(
            () ->
                new AgentProperties(
                    tempDir, new AgentProperties.History(-1, 10), new AgentProperties.Model(Duration.ZERO)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createsDedicatedWorkspaceAndWiresOfflineChatUseCase() throws Exception {
    Path workspace = tempDir.resolve("java-workspace");
    var properties =
        new AgentProperties(
            workspace,
            new AgentProperties.History(40, 100_000),
            new AgentProperties.Model(Duration.ofSeconds(5)));
    var configuration = new ApplicationConfiguration();
    var schema = configuration.sqliteSchema(properties);
    var jdbcRepository = configuration.jdbcSessionRepository(schema);
    var capturedRequest = new AtomicReference<io.namei.agent.kernel.model.ChatModelRequest>();
    var model =
        (io.namei.agent.kernel.port.ChatModelPort)
            request -> {
              capturedRequest.set(request);
              return new ChatModelResponse("离线回答");
            };

    var useCase =
        configuration.chatUseCase(
            configuration.sessionRepository(jdbcRepository),
            model,
            configuration.sessionExecutionGate(properties),
            properties,
            new ByteArrayResource("  系统提示  ".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var result = useCase.chat(new io.namei.agent.application.ChatCommand("demo", "问题"));

    assertThat(Files.isDirectory(workspace)).isTrue();
    assertThat(Files.isRegularFile(workspace.resolve("sessions.db"))).isTrue();
    assertThat(result.assistant().content()).isEqualTo("离线回答");
    assertThat(capturedRequest.get().messages().getFirst().content()).isEqualTo("系统提示");
    assertThat(jdbcRepository.load("demo").messages()).hasSize(2);
  }
}
