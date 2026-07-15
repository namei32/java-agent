package io.namei.agent.adapter.mcp;

import io.namei.agent.adapter.mcp.reference.McpJavaReferenceServer;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpReferenceServerSupport {
  private McpReferenceServerSupport() {}

  static McpServerDefinition server(Path workingDirectory, String scenario) {
    return server(workingDirectory, "docs", scenario);
  }

  static McpServerDefinition server(
      Path workingDirectory, String id, String scenario, String... scenarioArguments) {
    List<String> names = List.of("echo", "slow", "remote_error", "image", "env_probe");
    Map<String, McpToolPolicy> tools = new LinkedHashMap<>();
    names.forEach(name -> tools.put(name, new McpToolPolicy(true, ToolRisk.READ_ONLY)));
    List<String> arguments = new ArrayList<>();
    arguments.add("-cp");
    arguments.add(testClasspath());
    arguments.add(McpJavaReferenceServer.class.getName());
    arguments.add(scenario);
    arguments.addAll(List.of(scenarioArguments));
    return new McpServerDefinition(
        id,
        javaExecutable(),
        arguments,
        workingDirectory.toAbsolutePath().normalize(),
        List.of(),
        tools);
  }

  static McpSettings settings(Path workingDirectory, int maxWireBytes, int concurrency) {
    return new McpSettings(
        McpMode.STATIC_READ_ONLY,
        workingDirectory.resolve("unused-mcp-config.json").toAbsolutePath(),
        4,
        32,
        8,
        Duration.ofSeconds(2),
        Duration.ofSeconds(2),
        Duration.ofMillis(150),
        65_536,
        maxWireBytes,
        concurrency);
  }

  private static Path javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize();
  }

  private static String testClasspath() {
    return System.getProperty("java.class.path");
  }
}
