package io.namei.agent.adapter.mcp;

import java.util.Objects;

record McpCallOutcome(Status status, String text) {
  McpCallOutcome {
    Objects.requireNonNull(status, "status");
    text = text == null ? "" : text.strip();
    if (status == Status.SUCCESS && text.isBlank()) {
      text = "工具执行完成。";
    }
    if (status != Status.SUCCESS) {
      text = "";
    }
  }

  static McpCallOutcome success(String text) {
    return new McpCallOutcome(Status.SUCCESS, text);
  }

  static McpCallOutcome remoteError() {
    return new McpCallOutcome(Status.REMOTE_ERROR, "");
  }

  static McpCallOutcome unsupportedResult() {
    return new McpCallOutcome(Status.UNSUPPORTED_RESULT, "");
  }

  static McpCallOutcome timeout() {
    return new McpCallOutcome(Status.TIMEOUT, "");
  }

  static McpCallOutcome unavailable() {
    return new McpCallOutcome(Status.UNAVAILABLE, "");
  }

  enum Status {
    SUCCESS,
    REMOTE_ERROR,
    UNSUPPORTED_RESULT,
    TIMEOUT,
    UNAVAILABLE
  }
}
