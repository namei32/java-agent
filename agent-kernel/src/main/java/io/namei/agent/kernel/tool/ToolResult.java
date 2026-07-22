package io.namei.agent.kernel.tool;

import java.util.Objects;

/**
 * 工具执行后可安全反馈给模型的结果。
 *
 * @param status 成功、错误、超时、取消、拒绝或跳过状态
 * @param content 已脱敏的结果正文；空正文会按状态替换为稳定默认文本
 */
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

  public static ToolResult timeout() {
    return new ToolResult(ToolResultStatus.TIMEOUT, "工具执行超时。");
  }

  public static ToolResult cancelled() {
    return new ToolResult(ToolResultStatus.CANCELLED, "工具调用已取消。");
  }

  public static ToolResult denied() {
    return new ToolResult(ToolResultStatus.DENIED, "工具调用未获批准。");
  }

  public static ToolResult approvalExpired() {
    return new ToolResult(ToolResultStatus.DENIED, "工具审批已过期。");
  }

  public static ToolResult skipped() {
    return new ToolResult(ToolResultStatus.SKIPPED, "工具调用已跳过。");
  }
}
