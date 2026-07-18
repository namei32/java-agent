package io.namei.agent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.mcp.McpMode;
import io.namei.agent.adapter.mcp.McpRuntime;
import io.namei.agent.adapter.mcp.McpRuntimeStatus;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpBootstrapConfigurationTest {
  @TempDir Path tempDir;

  @Test
  void defaultsToDisabledWithoutReadingConfiguredFile() {
    var properties =
        new McpProperties(
            null,
            tempDir.resolve("不存在.json"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    McpRuntime runtime =
        new ApplicationConfiguration()
            .mcpRuntime(properties, agentProperties(ToolRuntimeMode.READ_ONLY));

    assertThat(properties.toSettings())
        .isEqualTo(io.namei.agent.adapter.mcp.McpSettings.disabled());
    assertThat(runtime.tools()).isEmpty();
    assertThat(runtime.status()).isEqualTo(McpRuntimeStatus.disabled());
  }

  @Test
  void rejectsStaticMcpBeforeReadingConfigWhenGlobalToolsAreDisabled() {
    var properties = staticProperties(tempDir.resolve("不存在.json"), Duration.ofSeconds(4));

    assertThatThrownBy(
            () ->
                new ApplicationConfiguration()
                    .mcpRuntime(properties, agentProperties(ToolRuntimeMode.DISABLED)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("启用 MCP 前必须启用全局 Tool Runtime");
  }

  @Test
  void requiresMcpRequestTimeoutToFitInsideToolTimeoutBeforeReadingConfig() {
    var properties = staticProperties(tempDir.resolve("不存在.json"), Duration.ofSeconds(5));

    assertThatThrownBy(
            () ->
                new ApplicationConfiguration()
                    .mcpRuntime(properties, agentProperties(ToolRuntimeMode.READ_ONLY)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("agent.mcp.request-timeout 必须小于 agent.tools.timeout");
  }

  @Test
  void combinesBuiltInAndMcpReadOnlyToolsAsAnImmutableSnapshot() {
    Tool mcpTool = readOnlyTool("mcp_docs_search");
    McpRuntime runtime = fixedRuntime(mcpTool);

    List<Tool> tools =
        new ApplicationConfiguration()
            .configuredTools(agentProperties(ToolRuntimeMode.READ_ONLY), runtime);

    assertThat(tools)
        .extracting(tool -> tool.definition().name())
        .containsExactly("current_time", "mcp_docs_search");
    assertThatThrownBy(() -> tools.add(readOnlyTool("another")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void publishesMcpToolsAsDeferredCatalogEntriesAndOnlyExposesSearchInitially() {
    var catalog =
        new ApplicationConfiguration()
            .configuredToolCatalog(
                agentProperties(ToolRuntimeMode.READ_ONLY),
                fixedRuntime(readOnlyTool("mcp_docs_search")));

    assertThat(catalog.initialDefinitions())
        .extracting(ToolDefinition::name)
        .containsExactly("current_time", "tool_search");
  }

  @Test
  void globalDisabledModePublishesNoBuiltInOrMcpTools() {
    List<Tool> tools =
        new ApplicationConfiguration()
            .configuredTools(
                agentProperties(ToolRuntimeMode.DISABLED), fixedRuntime(readOnlyTool("mcp_docs")));

    assertThat(tools).isEmpty();
  }

  @Test
  void rejectsNonReadOnlyToolAtBootstrapBoundary() {
    Tool writeTool =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return new ToolDefinition("mcp_write", "禁止的写工具", objectSchema(), ToolRisk.WRITE);
          }

          @Override
          public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success("不应执行");
          }
        };

    assertThatThrownBy(
            () ->
                new ApplicationConfiguration()
                    .configuredToolCatalog(
                        agentProperties(ToolRuntimeMode.READ_ONLY), fixedRuntime(writeTool)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MCP Runtime 暴露了非只读工具");
  }

  @Test
  void templatesKeepMcpDisabledExposeAllBoundsAndSilenceUntrustedSdkLogging() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
    String environmentTemplate = Files.readString(Path.of("../.env.example"));

    assertThat(yaml)
        .contains("mode: ${AGENT_MCP_MODE:DISABLED}")
        .contains("config-file: ${AGENT_MCP_CONFIG_FILE:}")
        .contains("max-servers: ${AGENT_MCP_MAX_SERVERS:4}")
        .contains("max-tools-per-server: ${AGENT_MCP_MAX_TOOLS_PER_SERVER:32}")
        .contains("max-list-pages: ${AGENT_MCP_MAX_LIST_PAGES:8}")
        .contains("connect-timeout: ${AGENT_MCP_CONNECT_TIMEOUT:5s}")
        .contains("request-timeout: ${AGENT_MCP_REQUEST_TIMEOUT:4s}")
        .contains("shutdown-timeout: ${AGENT_MCP_SHUTDOWN_TIMEOUT:2s}")
        .contains("max-schema-bytes: ${AGENT_MCP_MAX_SCHEMA_BYTES:65536}")
        .contains("max-wire-bytes: ${AGENT_MCP_MAX_WIRE_BYTES:1048576}")
        .contains("max-concurrent-calls-per-server: ${AGENT_MCP_MAX_CONCURRENT_CALLS_PER_SERVER:1}")
        .contains("io.modelcontextprotocol: \"OFF\"");
    assertThat(environmentTemplate)
        .contains("AGENT_MCP_MODE=DISABLED")
        .contains("AGENT_MCP_CONFIG_FILE=")
        .contains("AGENT_MCP_REQUEST_TIMEOUT=4s")
        .contains("AGENT_MCP_MAX_WIRE_BYTES=1048576")
        .contains("AGENT_MCP_MAX_CONCURRENT_CALLS_PER_SERVER=1");
  }

  @Test
  void productionBootstrapHasNoSdkDynamicManagementOrHttpTransportEntryPoint() throws Exception {
    try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        assertThat(Files.readString(source))
            .doesNotContain("io.modelcontextprotocol.")
            .doesNotContain("mcp_add")
            .doesNotContain("mcp_remove")
            .doesNotContain("StreamableHttp")
            .doesNotContain("HttpClientTransport");
      }
    }
    List<Class<?>> productionBeanTypes =
        java.util.Arrays.stream(ApplicationConfiguration.class.getDeclaredMethods())
            .filter(
                method ->
                    method.isAnnotationPresent(org.springframework.context.annotation.Bean.class))
            .map(java.lang.reflect.Method::getReturnType)
            .toList();
    assertThat(productionBeanTypes).contains(McpRuntime.class).doesNotContain(Tool.class);
  }

  private McpProperties staticProperties(Path configFile, Duration requestTimeout) {
    return new McpProperties(
        McpMode.STATIC_READ_ONLY,
        configFile,
        4,
        32,
        8,
        Duration.ofSeconds(5),
        requestTimeout,
        Duration.ofSeconds(2),
        65_536,
        1_048_576,
        1);
  }

  private AgentProperties agentProperties(ToolRuntimeMode mode) {
    return new AgentProperties(
        tempDir.resolve("workspace"),
        null,
        new AgentProperties.Model(Duration.ofSeconds(60)),
        null,
        new AgentProperties.Tools(mode, 8, 16, Duration.ofSeconds(5), 32, 16_384, 20_000),
        null);
  }

  private static McpRuntime fixedRuntime(Tool tool) {
    return new McpRuntime() {
      @Override
      public List<Tool> tools() {
        return List.of(tool);
      }

      @Override
      public McpRuntimeStatus status() {
        return new McpRuntimeStatus(McpRuntimeStatus.State.READY, 1, 1, 0, 0);
      }

      @Override
      public void close() {}
    };
  }

  private static Tool readOnlyTool(String name) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(name, "固定只读工具", objectSchema(), ToolRisk.READ_ONLY);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.success("固定结果");
      }
    };
  }

  private static Map<String, Object> objectSchema() {
    return Map.of("type", "object", "properties", Map.of());
  }
}
