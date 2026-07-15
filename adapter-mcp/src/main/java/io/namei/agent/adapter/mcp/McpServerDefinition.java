package io.namei.agent.adapter.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class McpServerDefinition {
  private final String id;
  private final Path executable;
  private final List<String> arguments;
  private final Path workingDirectory;
  private final List<String> environmentVariables;
  private final Map<String, McpToolPolicy> tools;

  McpServerDefinition(
      String id,
      Path executable,
      List<String> arguments,
      Path workingDirectory,
      List<String> environmentVariables,
      Map<String, McpToolPolicy> tools) {
    this.id = Objects.requireNonNull(id, "id");
    this.executable = Objects.requireNonNull(executable, "executable");
    this.arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    this.environmentVariables =
        List.copyOf(Objects.requireNonNull(environmentVariables, "environmentVariables"));
    this.tools = Map.copyOf(Objects.requireNonNull(tools, "tools"));
  }

  public String id() {
    return id;
  }

  public Path executable() {
    return executable;
  }

  public List<String> arguments() {
    return arguments;
  }

  public Path workingDirectory() {
    return workingDirectory;
  }

  public List<String> environmentVariables() {
    return environmentVariables;
  }

  public Map<String, McpToolPolicy> tools() {
    return tools;
  }

  @Override
  public String toString() {
    return "McpServerDefinition[id=" + id + "]";
  }
}
