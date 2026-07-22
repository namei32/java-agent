package io.namei.agent.adapter.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个静态 stdio MCP Server 的安全启动定义。
 *
 * <p>可执行文件、参数和工作目录均以 token/Path 形式保存，不经过 Shell；环境变量列表只声明允许从父进程读取的名称；工具映射决定哪些远程工具可发布及其风险。
 */
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

  /** 返回配置内唯一且可用于工具名称前缀的 Server ID。 */
  public String id() {
    return id;
  }

  /** 返回必须直接执行的绝对程序路径。 */
  public Path executable() {
    return executable;
  }

  /** 返回不经 Shell 展开的进程参数。 */
  public List<String> arguments() {
    return arguments;
  }

  /** 返回 Server 进程受限的固定工作目录。 */
  public Path workingDirectory() {
    return workingDirectory;
  }

  /** 返回允许显式传递给子进程的环境变量名称。 */
  public List<String> environmentVariables() {
    return environmentVariables;
  }

  /** 返回远程工具名到本地启用/风险策略的不可变映射。 */
  public Map<String, McpToolPolicy> tools() {
    return tools;
  }

  @Override
  public String toString() {
    return "McpServerDefinition[id=" + id + "]";
  }
}
