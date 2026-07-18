package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemorySearchHit;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Single deferred Tool for bounded, current-scope Java Native memory recall. */
public final class MemoryRecallToolset {
  static final String VERSION = "java-memory-recall-v1";
  static final int MAX_QUERY_CODE_POINTS = 256;
  static final int DEFAULT_LIMIT = 8;
  static final int MAX_LIMIT = 20;
  public static final int MAX_PROJECTED_CODE_POINTS = 12_000;
  static final String INVALID_ARGUMENT = "MEMORY_RECALL_INVALID_ARGUMENT";
  static final String UNAVAILABLE = "MEMORY_RECALL_UNAVAILABLE";
  private static final MemoryRecallToolset DISABLED = new MemoryRecallToolset(List.of());

  private final List<Tool> tools;

  private MemoryRecallToolset(List<Tool> tools) {
    this.tools = List.copyOf(tools);
  }

  public static MemoryRecallToolset disabled() {
    return DISABLED;
  }

  public static MemoryRecallToolset enabled() {
    return new MemoryRecallToolset(List.of(new RecallMemoryTool()));
  }

  public List<Tool> tools() {
    return tools;
  }

  private static final class RecallMemoryTool implements ContextualTool {
    private static final ToolDefinition DEFINITION =
        new ToolDefinition(
            "recall_memory",
            "在当前会话的 Java Native 记忆中检索相关条目。",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "query",
                    Map.of("type", "string"),
                    "memory_type",
                    Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("NOTE", "FACT", "PREFERENCE", "PROCEDURE", "EVENT")),
                    "limit",
                    Map.of("type", "integer")),
                "required",
                List.of("query"),
                "additionalProperties",
                false),
            ToolRisk.READ_ONLY,
            VERSION);

    @Override
    public ToolDefinition definition() {
      return DEFINITION;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
      return ToolResult.error(UNAVAILABLE);
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolInvocationContext context) {
      RecallRequest request;
      try {
        request = parse(arguments);
      } catch (IllegalArgumentException invalid) {
        return ToolResult.error(INVALID_ARGUMENT);
      }
      try {
        MemoryRecallScope scope = context.memoryRecallScope().orElseThrow();
        List<MemorySearchHit> hits =
            scope.recall(request.query(), request.memoryType(), request.limit());
        if (hits.size() > request.limit()) {
          return ToolResult.error(UNAVAILABLE);
        }
        return ToolResult.success(render(hits, request.limit()));
      } catch (RuntimeException unavailable) {
        return ToolResult.error(UNAVAILABLE);
      }
    }
  }

  private static RecallRequest parse(Map<String, Object> arguments) {
    if (arguments == null
        || !Set.of("query", "memory_type", "limit").containsAll(arguments.keySet())) {
      throw new IllegalArgumentException("参数无效");
    }
    Object rawQuery = arguments.get("query");
    if (!(rawQuery instanceof String query)) {
      throw new IllegalArgumentException("query 无效");
    }
    query = query.strip();
    if (query.isBlank() || query.codePointCount(0, query.length()) > MAX_QUERY_CODE_POINTS) {
      throw new IllegalArgumentException("query 超出范围");
    }
    Optional<MemoryType> memoryType = Optional.empty();
    if (arguments.containsKey("memory_type")) {
      Object rawType = arguments.get("memory_type");
      if (!(rawType instanceof String type)) {
        throw new IllegalArgumentException("memory_type 无效");
      }
      try {
        memoryType = Optional.of(MemoryType.valueOf(type));
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("memory_type 无效", invalid);
      }
    }
    return new RecallRequest(
        query, memoryType, integer(arguments.get("limit"), DEFAULT_LIMIT, 1, MAX_LIMIT));
  }

  private static int integer(Object raw, int defaultValue, int minimum, int maximum) {
    if (raw == null) {
      return defaultValue;
    }
    long value;
    if (raw instanceof Byte
        || raw instanceof Short
        || raw instanceof Integer
        || raw instanceof Long) {
      value = ((Number) raw).longValue();
    } else if (raw instanceof BigInteger integer) {
      value = integer.longValueExact();
    } else if (raw instanceof BigDecimal decimal) {
      if (decimal.scale() != 0) {
        throw new IllegalArgumentException("数值必须是整数");
      }
      value = decimal.longValueExact();
    } else {
      throw new IllegalArgumentException("数值必须是整数");
    }
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException("数值超出范围");
    }
    return Math.toIntExact(value);
  }

  private static String render(List<MemorySearchHit> hits, int limit) {
    int contentCodePoints =
        hits.stream()
            .map(MemorySearchHit::item)
            .mapToInt(item -> item.content().codePointCount(0, item.content().length()))
            .sum();
    if (contentCodePoints > MAX_PROJECTED_CODE_POINTS) {
      throw new IllegalStateException("Memory recall 投影超过预算");
    }
    var output =
        new StringBuilder("{\"count\":")
            .append(hits.size())
            .append(",\"limit\":")
            .append(limit)
            .append(",\"items\":[");
    for (int index = 0; index < hits.size(); index++) {
      if (index > 0) {
        output.append(',');
      }
      MemorySearchHit hit = hits.get(index);
      output
          .append("{\"id\":")
          .append(ToolCatalogJson.string(hit.item().id()))
          .append(",\"memory_type\":")
          .append(ToolCatalogJson.string(hit.item().type().name()))
          .append(",\"content\":")
          .append(ToolCatalogJson.string(hit.item().content()))
          .append(",\"score\":")
          .append(String.format(java.util.Locale.ROOT, "%.4f", hit.finalScore()))
          .append('}');
    }
    return output.append("]}").toString();
  }

  private record RecallRequest(String query, Optional<MemoryType> memoryType, int limit) {}
}
