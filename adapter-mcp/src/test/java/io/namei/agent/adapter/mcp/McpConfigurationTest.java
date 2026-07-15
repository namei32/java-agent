package io.namei.agent.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class McpConfigurationTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void disabledModePerformsNoConfigurationFileAccess() {
    AtomicInteger reads = new AtomicInteger();
    McpConfigLoader loader =
        new McpConfigLoader(
            (path, maxBytes) -> {
              reads.incrementAndGet();
              throw new AssertionError("DISABLED 不得读取配置");
            });

    McpConfiguration configuration = loader.load(McpSettings.disabled());

    assertThat(configuration.servers()).isEmpty();
    assertThat(reads).hasValue(0);
  }

  @Test
  void loadsStrictStaticConfigurationAndKeepsOnlyEnvironmentNames() throws Exception {
    Path executable = javaExecutable();
    Path config = writeConfig(validDocument(executable, temp));

    McpConfiguration configuration =
        new McpConfigLoader().load(McpSettings.staticReadOnlyDefaults(config));

    assertThat(configuration.schemaVersion()).isEqualTo(1);
    assertThat(configuration.servers()).hasSize(1);
    McpServerDefinition server = configuration.servers().getFirst();
    assertThat(server.id()).isEqualTo("docs");
    assertThat(server.executable()).isEqualTo(executable.toRealPath());
    assertThat(server.arguments()).containsExactly("-jar", "/opt/namei/docs-mcp.jar");
    assertThat(server.workingDirectory()).isEqualTo(temp.toRealPath());
    assertThat(server.environmentVariables()).containsExactly("DOCS_MCP_TOKEN");
    assertThat(server.tools().get("search")).isEqualTo(new McpToolPolicy(true, ToolRisk.READ_ONLY));
    assertThat(server.toString())
        .isEqualTo("McpServerDefinition[id=docs]")
        .doesNotContain(executable.toString(), temp.toString());
  }

  @Test
  void rejectsUnknownDuplicateAndTrailingJsonWithoutLeakingInput() throws Exception {
    Path executable = javaExecutable();
    String valid = validDocument(executable, temp);
    List<String> invalidDocuments =
        List.of(
            valid.replaceFirst("\\{", "{\"unknown\":\"PRIVATE_TOKEN\","),
            valid.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid + " {\"second\":true}");

    for (String invalid : invalidDocuments) {
      Path config = writeConfig(invalid);
      assertThatThrownBy(
              () -> new McpConfigLoader().load(McpSettings.staticReadOnlyDefaults(config)))
          .isInstanceOf(McpConfigurationException.class)
          .hasMessage("MCP 静态配置无效。")
          .hasMessageNotContaining("PRIVATE_TOKEN")
          .hasMessageNotContaining(executable.toString())
          .hasMessageNotContaining(temp.toString());
    }
  }

  @Test
  void rejectsSymlinkedOrOversizedConfigurationBeforeParsing() throws Exception {
    Path target = writeConfig(validDocument(javaExecutable(), temp));
    Path link = temp.resolve("linked-mcp.json");
    Files.createSymbolicLink(link, target.getFileName());

    assertThatThrownBy(() -> new McpConfigLoader().load(McpSettings.staticReadOnlyDefaults(link)))
        .isInstanceOf(McpConfigurationException.class)
        .hasMessage("MCP 静态配置无效。");

    Path oversized = temp.resolve("oversized.json");
    Files.write(oversized, new byte[McpConfigLoader.MAX_CONFIG_BYTES + 1]);
    assertThatThrownBy(
            () -> new McpConfigLoader().load(McpSettings.staticReadOnlyDefaults(oversized)))
        .isInstanceOf(McpConfigurationException.class)
        .hasMessage("MCP 静态配置无效。");
  }

  @Test
  void rejectsRelativePathsShellsDuplicateIdsUnsafePoliciesAndEnvironmentNames() throws Exception {
    String valid = validDocument(javaExecutable(), temp);
    List<Map.Entry<String, String>> invalidDocuments =
        List.of(
            Map.entry(
                "relative executable",
                valid.replace(JSON.writeValueAsString(javaExecutable().toString()), "\"java\"")),
            Map.entry(
                "relative working directory",
                valid.replace(JSON.writeValueAsString(temp.toString()), "\"runtime\"")),
            Map.entry(
                "shell executable",
                valid.replace(
                    JSON.writeValueAsString(javaExecutable().toString()),
                    JSON.writeValueAsString(Path.of("/bin/sh").toString()))),
            Map.entry("invalid server id", valid.replace("\"docs\"", "\"Docs.Invalid\"")),
            Map.entry(
                "invalid environment name", valid.replace("\"DOCS_MCP_TOKEN\"", "\"BAD-NAME\"")),
            Map.entry("unsafe policy", valid.replace("\"READ_ONLY\"", "\"WRITE\"")),
            Map.entry(
                "duplicate server",
                JSON.writeValueAsString(
                    Map.of(
                        "schemaVersion",
                        1,
                        "servers",
                        List.of(
                            validServer(javaExecutable(), temp),
                            validServer(javaExecutable(), temp))))));

    for (Map.Entry<String, String> invalid : invalidDocuments) {
      Path config = writeConfig(invalid.getValue());
      assertThatThrownBy(
              () -> new McpConfigLoader().load(McpSettings.staticReadOnlyDefaults(config)))
          .as(invalid.getKey())
          .isInstanceOf(McpConfigurationException.class)
          .hasMessage("MCP 静态配置无效。");
    }
  }

  @Test
  void clearsChildEnvironmentBeforeCopyingExplicitAllowlist() {
    ProcessBuilder builder = new ProcessBuilder(javaExecutable().toString(), "-version");
    builder.environment().clear();
    builder.environment().put("HOME", "/private/home");
    builder.environment().put("PROVIDER_API_KEY", "private-provider-key");
    Map<String, String> parent =
        Map.of(
            "DOCS_MCP_TOKEN", "private-mcp-token",
            "HOME", "/private/home",
            "PROVIDER_API_KEY", "private-provider-key");

    McpProcessEnvironment.replace(
        builder, List.of("DOCS_MCP_TOKEN", "MISSING_ALLOWED_VALUE"), parent);

    assertThat(builder.environment())
        .containsOnly(Map.entry("DOCS_MCP_TOKEN", "private-mcp-token"))
        .doesNotContainKeys("HOME", "PATH", "PROVIDER_API_KEY", "MISSING_ALLOWED_VALUE");
  }

  @Test
  void validatesAllGlobalSafetyBounds() {
    assertThatThrownBy(
            () ->
                new McpSettings(
                    McpMode.STATIC_READ_ONLY,
                    temp.resolve("config.json"),
                    0,
                    32,
                    8,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(4),
                    Duration.ofSeconds(2),
                    65_536,
                    1_048_576,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new McpSettings(
                    McpMode.STATIC_READ_ONLY,
                    temp.resolve("config.json"),
                    4,
                    32,
                    8,
                    Duration.ZERO,
                    Duration.ofSeconds(4),
                    Duration.ofSeconds(2),
                    65_536,
                    1_048_576,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(McpSettings.disabled().toString())
        .isEqualTo("McpSettings[mode=DISABLED]")
        .doesNotContain(temp.toString());
  }

  private Path writeConfig(String document) throws Exception {
    Path path = Files.createTempFile(temp, "mcp-", ".json");
    Files.writeString(path, document);
    return path;
  }

  private static Path javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize();
  }

  private static String validDocument(Path executable, Path workingDirectory) throws Exception {
    return JSON.writeValueAsString(
        Map.of("schemaVersion", 1, "servers", List.of(validServer(executable, workingDirectory))));
  }

  private static Map<String, Object> validServer(Path executable, Path workingDirectory) {
    Map<String, Object> toolPolicy = Map.of("enabled", true, "risk", "READ_ONLY");
    Map<String, Object> server = new LinkedHashMap<>();
    server.put("id", "docs");
    server.put("transport", "STDIO");
    server.put("executable", executable.toString());
    server.put("arguments", List.of("-jar", "/opt/namei/docs-mcp.jar"));
    server.put("workingDirectory", workingDirectory.toString());
    server.put("environmentVariables", List.of("DOCS_MCP_TOKEN"));
    server.put("tools", Map.of("search", toolPolicy));
    return server;
  }
}
