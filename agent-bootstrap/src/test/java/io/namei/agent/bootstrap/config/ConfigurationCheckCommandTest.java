package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ConfigurationCheckCommandTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void printsAValidRedactedReportWithoutCreatingRuntimeArtifacts() throws Exception {
    Path configFile = tempDir.resolve("config.toml");
    Files.writeString(
        configFile,
        """
        [llm]
        provider = "deepseek"

        [llm.main]
        model = "deepseek-chat"
        api_key = "${DEEPSEEK_API_KEY}"

        [agent]
        system_prompt = "private prompt must not be printed"

        [plugins.example]
        enabled = true

        [future_extension]
        value = "unknown private value"
        """);
    var output = new ByteArrayOutputStream();

    int exitCode =
        ConfigurationCheckCommand.run(
            new String[] {"--agent.config-check", "--agent.config-file=" + configFile},
            Map.of("DEEPSEEK_API_KEY", "private-secret-must-not-be-printed"),
            tempDir,
            new PrintStream(output, true, StandardCharsets.UTF_8));
    String rendered = output.toString(StandardCharsets.UTF_8);
    JsonNode report = JSON.readTree(rendered);

    assertThat(exitCode).isZero();
    assertThat(report.path("valid").asBoolean()).isTrue();
    assertThat(report.path("mode").asString()).isEqualTo("TOML");
    assertThat(report.path("configFile").asString())
        .isEqualTo(configFile.toAbsolutePath().normalize().toString());
    assertThat(report.path("active").toString())
        .contains("apiKey", "PRESENT", "TOML_MODERN", "model", "PRESET");
    assertThat(report.path("deferredPaths").toString()).contains("plugins.example.enabled");
    assertThat(report.path("unknownPaths").toString()).contains("future_extension.value");
    assertThat(rendered)
        .doesNotContain(
            "private-secret-must-not-be-printed",
            "private prompt must not be printed",
            "unknown private value");
    assertThat(Files.exists(tempDir.resolve("workspace"))).isFalse();
    assertThat(Files.exists(tempDir.resolve("sessions.db"))).isFalse();
    assertThat(Files.readString(configFile)).contains("private prompt must not be printed");
  }

  @Test
  void returnsStableDiagnosticsForInvalidConfigurationWithoutLeakingValues() throws Exception {
    Path configFile = tempDir.resolve("invalid.toml");
    Files.writeString(
        configFile,
        """
        [llm]
        provider = "deepseek"

        [llm.main]
        model = "deepseek-chat"
        api_key = "invalid-secret-value"
        base_url = "ftp://private.invalid.example/v1"
        """);
    var output = new ByteArrayOutputStream();

    int exitCode =
        ConfigurationCheckCommand.run(
            new String[] {"--agent.config-check", "--agent.config-file=" + configFile},
            Map.of(),
            tempDir,
            new PrintStream(output, true, StandardCharsets.UTF_8));
    String rendered = output.toString(StandardCharsets.UTF_8);
    JsonNode report = JSON.readTree(rendered);

    assertThat(exitCode).isEqualTo(2);
    assertThat(report.path("valid").asBoolean()).isFalse();
    assertThat(report.path("diagnostics").toString())
        .contains("CONFIG_URL_INVALID", "llm.main.base_url");
    assertThat(rendered).doesNotContain("invalid-secret-value", "ftp://private.invalid.example");
    assertThat(Files.exists(tempDir.resolve("workspace"))).isFalse();
    assertThat(Files.exists(tempDir.resolve("sessions.db"))).isFalse();
  }

  @Test
  void recognizesOnlyTheExplicitCheckSwitch() {
    assertThat(ConfigurationCheckCommand.isRequested(new String[] {"--agent.config-check"}))
        .isTrue();
    assertThat(ConfigurationCheckCommand.isRequested(new String[] {"--agent.config-check=true"}))
        .isTrue();
    assertThat(ConfigurationCheckCommand.isRequested(new String[] {"--agent.config-check=false"}))
        .isFalse();
    assertThat(ConfigurationCheckCommand.isRequested(new String[] {"--agent.config-file=x.toml"}))
        .isFalse();
  }
}
