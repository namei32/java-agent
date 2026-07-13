package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ConfigurationResolverTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void resolvesEveryPythonConfigurationGoldenCase() throws Exception {
    JsonNode fixture = readFixture("configuration/config-resolution.json");
    var resolver = new ConfigurationResolver();

    for (JsonNode testCase : fixture.path("cases")) {
      Path caseDirectory = Files.createDirectory(tempDir.resolve(testCase.path("id").asString()));
      Path configFile = caseDirectory.resolve("config.toml");
      String toml = testCase.path("input").path("toml").asString();
      Files.writeString(configFile, toml);
      var environment = stringMap(testCase.path("input").path("environment"));

      var resolution =
          resolver.resolve(new ConfigurationInputs(caseDirectory, configFile.toString(), environment));
      var active = resolution.requireActive();
      JsonNode expected = testCase.path("expected").path("active");

      assertThat(resolution.mode()).as(testCase.path("id").asString()).isEqualTo(ConfigurationMode.TOML);
      assertThat(active.provider()).isEqualTo(expected.path("provider").asString());
      assertThat(active.model()).isEqualTo(expected.path("model").asString());
      assertThat(active.apiKey().status().name())
          .isEqualTo(expected.path("apiKeyStatus").asString());
      assertThat(active.baseUrl().toString()).isEqualTo(expected.path("baseUrl").asString());
      assertThat(active.systemPrompt().orElseThrow())
          .isEqualTo(expected.path("systemPrompt").asString());
      assertThat(active.historyMaxMessages())
          .isEqualTo(expected.path("historyMaxMessages").asInt());
      assertThat(resolution.report().diagnostics()).isEmpty();
      assertThat(Files.readString(configFile)).isEqualTo(toml);
    }
  }

  @Test
  void keepsEnvironmentModeAndUsesExplicitSourcePriority() throws Exception {
    var environment =
        Map.of(
            "OPENAI_BASE_URL", "https://environment.example.test/v1",
            "OPENAI_API_KEY", "environment-secret",
            "OPENAI_MODEL", "environment-model");

    var inputs = new ConfigurationInputs(tempDir, null, environment);
    assertThat(inputs.toString()).doesNotContain("environment-secret");
    var environmentResolution = new ConfigurationResolver().resolve(inputs);
    var environmentActive = environmentResolution.requireActive();

    assertThat(environmentResolution.mode()).isEqualTo(ConfigurationMode.ENVIRONMENT);
    assertThat(environmentResolution.configFile()).isEmpty();
    assertThat(environmentActive.model()).isEqualTo("environment-model");
    assertThat(environmentActive.systemPrompt()).isEmpty();
    assertThat(environmentActive.historyMaxMessages()).isEqualTo(40);
    assertThat(environmentActive.source("model")).isEqualTo(ConfigurationSource.ENV);
    assertThat(environmentActive.apiKey().toString()).isEqualTo("[REDACTED]");
    assertThat(tempDir).isEmptyDirectory();

    Path defaultFile = tempDir.resolve("config.toml");
    Files.writeString(defaultFile, validToml("default-provider", "default-model"));
    Path namedFile = tempDir.resolve("named.toml");
    Files.writeString(namedFile, validToml("named-provider", "named-model"));
    Path commandFile = tempDir.resolve("command.toml");
    Files.writeString(commandFile, validToml("command-provider", "command-model"));
    var overrides = new HashMap<String, String>();
    overrides.put("NAMEI_CONFIG_FILE", namedFile.toString());
    overrides.put("OPENAI_MODEL", "override-model");
    overrides.put("OPENAI_API_KEY", "override-secret");
    overrides.put("OPENAI_BASE_URL", "https://override.example.test/v1");

    var commandResolution =
        new ConfigurationResolver()
            .resolve(new ConfigurationInputs(tempDir, commandFile.toString(), overrides));
    var commandActive = commandResolution.requireActive();

    assertThat(commandResolution.configFile()).contains(commandFile.toAbsolutePath().normalize());
    assertThat(commandActive.provider()).isEqualTo("command-provider");
    assertThat(commandActive.model()).isEqualTo("override-model");
    assertThat(commandActive.source("provider")).isEqualTo(ConfigurationSource.TOML_MODERN);
    assertThat(commandActive.source("model")).isEqualTo(ConfigurationSource.ENV);
  }

  @Test
  void reportsValidationGoldenDiagnosticsWithoutLeakingValues() throws Exception {
    JsonNode fixture = readFixture("configuration/config-validation.json");
    var resolver = new ConfigurationResolver();

    for (JsonNode testCase : fixture.path("cases")) {
      Path caseDirectory = Files.createDirectory(tempDir.resolve(testCase.path("id").asString()));
      Path configFile = caseDirectory.resolve("config.toml");
      Files.writeString(configFile, testCase.path("input").path("toml").asString());

      var resolution =
          resolver.resolve(
              new ConfigurationInputs(
                  caseDirectory,
                  configFile.toString(),
                  stringMap(testCase.path("input").path("environment"))));

      assertThat(resolution.active()).isEmpty();
      assertThat(diagnosticKeys(resolution.report().diagnostics()))
          .containsExactlyElementsOf(expectedDiagnosticKeys(testCase));
      assertThatThrownBy(resolution::requireActive)
          .isInstanceOf(ConfigurationResolutionException.class)
          .hasMessageNotContaining("__GOLDEN_SECRET__")
          .hasMessageNotContaining("ftp://invalid.example.test");
    }
  }

  @Test
  void classifiesDeferredAndUnknownPathsAndRejectsInvalidFiles() throws Exception {
    Path configFile = tempDir.resolve("classified.toml");
    String toml =
        validToml("deepseek", "deepseek-chat")
            + """

                [llm.fast]
                model = "deferred-model"

                [agent.tools]
                search_enabled = true

                [future_extension]
                unknown_value = "unknown"
                """;
    Files.writeString(configFile, toml);

    var resolution =
        new ConfigurationResolver()
            .resolve(new ConfigurationInputs(tempDir, configFile.toString(), Map.of()));

    assertThat(resolution.report().deferredPaths())
        .contains("llm.fast.model", "agent.tools.search_enabled");
    assertThat(resolution.report().unknownPaths())
        .containsExactly("future_extension.unknown_value");
    assertThat(Files.readString(configFile)).isEqualTo(toml);

    Path missing = tempDir.resolve("missing.toml");
    var missingResolution =
        new ConfigurationResolver()
            .resolve(new ConfigurationInputs(tempDir, missing.toString(), Map.of()));
    assertThat(diagnosticKeys(missingResolution.report().diagnostics()))
        .containsExactly("CONFIG_FILE_NOT_FOUND|$document");

    Path wrongExtension = tempDir.resolve("config.txt");
    Files.writeString(wrongExtension, "provider = \"deepseek\"");
    var invalidResolution =
        new ConfigurationResolver()
            .resolve(new ConfigurationInputs(tempDir, wrongExtension.toString(), Map.of()));
    assertThat(diagnosticKeys(invalidResolution.report().diagnostics()))
        .containsExactly("CONFIG_FILE_INVALID|$document");
  }

  @Test
  void preservesProvidedSecretsAndReportsTheActualFailingSource() throws Exception {
    var environment =
        Map.of(
            "OPENAI_BASE_URL", "https://environment.example.test/v1",
            "OPENAI_API_KEY", "  exact-secret  ",
            "OPENAI_MODEL", "environment-model");
    var environmentActive =
        new ConfigurationResolver()
            .resolve(new ConfigurationInputs(tempDir, null, environment))
            .requireActive();
    assertThat(environmentActive.apiKey().reveal()).isEqualTo("  exact-secret  ");

    Path legacyFile = tempDir.resolve("legacy.toml");
    Files.writeString(
        legacyFile,
        """
        provider = "deepseek"
        model = "deepseek-chat"
        api_key = "${NAMEI_GOLDEN_MISSING_KEY}"
        base_url = "https://api.deepseek.com/v1"
        """);
    var legacyResolution =
        new ConfigurationResolver()
            .resolve(new ConfigurationInputs(tempDir, legacyFile.toString(), Map.of()));
    assertThat(diagnosticKeys(legacyResolution.report().diagnostics()))
        .containsExactly("CONFIG_ENV_UNRESOLVED|api_key");

    Path modernFile = tempDir.resolve("modern.toml");
    Files.writeString(modernFile, validToml("deepseek", "deepseek-chat"));
    var invalidOverride =
        new ConfigurationResolver()
            .resolve(
                new ConfigurationInputs(
                    tempDir,
                    modernFile.toString(),
                    Map.of("OPENAI_BASE_URL", "ftp://invalid.example.test/v1")));
    assertThat(diagnosticKeys(invalidOverride.report().diagnostics()))
        .containsExactly("CONFIG_URL_INVALID|OPENAI_BASE_URL");
  }

  private static String validToml(String provider, String model) {
    return """
        [llm]
        provider = "%s"

        [llm.main]
        model = "%s"
        api_key = "__GOLDEN_SECRET__"
        base_url = "https://model.example.test/v1"
        """
        .formatted(provider, model);
  }

  private static JsonNode readFixture(String relativePath) throws Exception {
    return JSON.readTree(goldenRoot().resolve(relativePath));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private static Map<String, String> stringMap(JsonNode node) {
    var values = new HashMap<String, String>();
    node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
    return values;
  }

  private static java.util.List<String> expectedDiagnosticKeys(JsonNode testCase) {
    var keys = new java.util.ArrayList<String>();
    for (JsonNode diagnostic : testCase.path("expected").path("diagnostics")) {
      keys.add(diagnostic.path("code").asString() + "|" + diagnostic.path("field").asString());
    }
    return keys;
  }

  private static java.util.List<String> diagnosticKeys(
      java.util.List<ConfigurationDiagnostic> diagnostics) {
    return diagnostics.stream()
        .map(diagnostic -> diagnostic.code().name() + "|" + diagnostic.field())
        .toList();
  }
}
