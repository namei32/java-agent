package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

class ConfigurationEnvironmentPostProcessorTest {
  @TempDir Path tempDir;

  @Test
  void injectsTomlBeforeSpringAiAndAgentProperties() throws Exception {
    Path configFile = tempDir.resolve("deepseek.toml");
    Files.writeString(
        configFile,
        """
        [llm]
        provider = "deepseek"

        [llm.main]
        model = "toml-model"
        api_key = "${DEEPSEEK_API_KEY}"
        base_url = "https://api.deepseek.com/v1"

        [agent]
        system_prompt = "包含 ${NOT_A_SPRING_PROPERTY} 的提示"

        [agent.context]
        memory_window = 18
        """);
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new SimpleCommandLinePropertySource(
                CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
                "--agent.config-file=" + configFile));
    var processor =
        new AgentConfigurationEnvironmentPostProcessor(
            tempDir,
            Map.of(
                "DEEPSEEK_API_KEY", "resolved-secret",
                "OPENAI_MODEL", "environment-model"));

    processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo("https://api.deepseek.com/v1");
    assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("resolved-secret");
    assertThat(environment.getProperty("spring.ai.openai.chat.model"))
        .isEqualTo("environment-model");
    assertThat(environment.getProperty("agent.history.max-messages", Integer.class)).isEqualTo(18);
    assertThat(environment.getProperty("agent.compatibility.mode")).isEqualTo("TOML");
    assertThat(
            new String(
                Base64.getDecoder()
                    .decode(
                        environment.getRequiredProperty(
                            "agent.compatibility.system-prompt-base64")),
                StandardCharsets.UTF_8))
        .isEqualTo("包含 ${NOT_A_SPRING_PROPERTY} 的提示");
    assertThat(environment.getPropertySources().iterator().next().getName())
        .isEqualTo(AgentConfigurationEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
  }

  @Test
  void leavesEnvironmentModeUntouchedAndFailsTomlBeforeContextCreation() throws Exception {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "test", Map.of("spring.ai.openai.chat.model", "existing-model")));
    var processor = new AgentConfigurationEnvironmentPostProcessor(tempDir, Map.of());

    processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

    assertThat(environment.getProperty("spring.ai.openai.chat.model")).isEqualTo("existing-model");
    assertThat(
            environment
                .getPropertySources()
                .contains(AgentConfigurationEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
        .isFalse();
    assertThat(tempDir).isEmptyDirectory();

    Path invalid = tempDir.resolve("invalid.toml");
    Files.writeString(invalid, "[llm]\nprovider = [\"deepseek\"");
    var invalidEnvironment = new StandardEnvironment();
    invalidEnvironment
        .getPropertySources()
        .addFirst(
            new SimpleCommandLinePropertySource(
                CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
                "--agent.config-file=" + invalid));

    assertThatThrownBy(
            () ->
                processor.postProcessEnvironment(
                    invalidEnvironment, new SpringApplication(Object.class)))
        .isInstanceOf(ConfigurationResolutionException.class)
        .hasMessageContaining("CONFIG_TOML_INVALID")
        .hasMessageNotContaining("deepseek");
  }

  @Test
  void registersThePostProcessorThroughSpringFactories() throws Exception {
    var factories = new Properties();
    try (var resource =
        getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
      assertThat(resource).isNotNull();
      factories.load(resource);
    }

    assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
        .contains(AgentConfigurationEnvironmentPostProcessor.class.getName());
  }
}
