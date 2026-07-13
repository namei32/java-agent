package io.namei.agent.kernel.tool;

import java.util.Objects;

public record ToolResult(ToolResultStatus status, String content) {
  public ToolResult {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(content, "content");
    content = content.strip();
    if (content.isBlank()) {
      content = status == ToolResultStatus.SUCCESS ? "工具执行完成。" : "工具执行失败。";
    }
  }

  public static ToolResult success(String content) {
    return new ToolResult(ToolResultStatus.SUCCESS, content);
  }

  public static ToolResult error(String content) {
    return new ToolResult(ToolResultStatus.ERROR, content);
  }
}
