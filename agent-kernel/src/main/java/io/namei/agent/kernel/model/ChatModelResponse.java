package io.namei.agent.kernel.model;

import io.namei.agent.kernel.tool.ToolCall;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ChatModelResponse(
    String content, List<ToolCall> toolCalls, Optional<ProviderCacheUsage> cacheUsage) {
  public ChatModelResponse(String content) {
    this(content, List.of(), Optional.empty());
  }

  public ChatModelResponse(String content, List<ToolCall> toolCalls) {
    this(content, toolCalls, Optional.empty());
  }

  public ChatModelResponse(
      String content, List<ToolCall> toolCalls, ProviderCacheUsage cacheUsage) {
    this(content, toolCalls, Optional.ofNullable(cacheUsage));
  }

  public ChatModelResponse {
    content = content == null ? "" : content.strip();
    toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    cacheUsage = Objects.requireNonNull(cacheUsage, "cacheUsage");
    if (content.isBlank() && toolCalls.isEmpty()) {
      throw new IllegalArgumentException("模型响应必须包含文本或 Tool Call");
    }
    var identifiers = new HashSet<String>();
    for (ToolCall call : toolCalls) {
      if (!identifiers.add(call.id())) {
        throw new IllegalArgumentException("Tool Call ID 重复: " + call.id());
      }
    }
  }

  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }
}
