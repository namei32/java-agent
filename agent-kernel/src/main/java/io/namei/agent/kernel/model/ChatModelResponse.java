package io.namei.agent.kernel.model;

import io.namei.agent.kernel.tool.ToolCall;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 表示一次模型调用经过核心层校验后的结果。
 *
 * @param content 模型生成的文本；允许在存在工具调用时为空
 * @param toolCalls 模型请求执行的工具调用，调用 ID 在单次响应内必须唯一
 * @param cacheUsage 供应商返回的缓存使用量；供应商不支持时为空
 * @param reasoning 允许跨轮续传的受信推理数据；普通文本推理不应放入此字段
 */
public record ChatModelResponse(
    String content,
    List<ToolCall> toolCalls,
    Optional<ProviderCacheUsage> cacheUsage,
    Optional<ProviderReasoning> reasoning) {
  public ChatModelResponse(String content) {
    this(content, List.of(), Optional.empty(), Optional.empty());
  }

  public ChatModelResponse(String content, List<ToolCall> toolCalls) {
    this(content, toolCalls, Optional.empty(), Optional.empty());
  }

  public ChatModelResponse(
      String content, List<ToolCall> toolCalls, ProviderCacheUsage cacheUsage) {
    this(content, toolCalls, Optional.ofNullable(cacheUsage), Optional.empty());
  }

  public ChatModelResponse(
      String content,
      List<ToolCall> toolCalls,
      ProviderCacheUsage cacheUsage,
      ProviderReasoning reasoning) {
    this(content, toolCalls, Optional.ofNullable(cacheUsage), Optional.ofNullable(reasoning));
  }

  public ChatModelResponse {
    content = content == null ? "" : content.strip();
    toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    cacheUsage = Objects.requireNonNull(cacheUsage, "cacheUsage");
    reasoning = Objects.requireNonNull(reasoning, "reasoning");
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

  /**
   * 判断模型是否要求进入工具执行循环。
   *
   * @return 至少包含一个工具调用时返回 {@code true}
   */
  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }
}
