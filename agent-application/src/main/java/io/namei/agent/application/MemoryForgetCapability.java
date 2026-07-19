package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryForgetCommand;
import io.namei.agent.kernel.memory.MemoryForgetResult;
import io.namei.agent.kernel.port.MemorySoftForgetPort;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one static, least-privilege implementation of the approved {@code forget_memory} capability.
 *
 * <p>It has no catalog, HTTP, approval, or Session write permission. The caller must first supply a
 * store-authenticated Capsule and acquire the durable Reservation; this type only derives the
 * current Scope and internal operation key before it reaches the narrow SoftForget port.
 */
public final class MemoryForgetCapability {
  public static final String TOOL_NAME = "forget_memory";
  public static final String TOOL_VERSION = "java-memory-forget-v1";
  public static final String EXECUTION_BOUNDARY_VERSION = "memory-forget-capability-v1";
  static final String PROJECTION_VERSION = "memory-forget-pending-projection-v1";
  static final String SAFE_COMPLETION_PROJECTION = "已完成获批的记忆遗忘操作。";
  private static final int MAX_SAFE_RESULT_CHARACTERS = 4_096;
  private static final ToolDefinition DEFINITION =
      new ToolDefinition(
          TOOL_NAME,
          "在当前会话范围内遗忘已批准的记忆条目。",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of("ids", Map.of("type", "array", "items", Map.of("type", "string"))),
              "required",
              List.of("ids"),
              "additionalProperties",
              false),
          ToolRisk.WRITE,
          TOOL_VERSION);

  private final MemorySoftForgetPort store;
  private final Clock clock;

  public MemoryForgetCapability(MemorySoftForgetPort store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public static ToolDefinition definition() {
    return DEFINITION;
  }

  /**
   * Returns false for any binding, schema, scope, or result-budget mismatch without invoking IO.
   */
  boolean accepts(PendingOperation operation, PendingOperationCapsule capsule) {
    try {
      requireBound(operation, capsule);
      assertSafeResultBudget(normalizeArguments(capsule.toToolCall().arguments()));
      return true;
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return false;
    }
  }

  /** Invokes only the narrow soft-forget port after the recovery coordinator has reserved it. */
  ToolResult invoke(PendingOperation operation, PendingOperationCapsule capsule) {
    requireBound(operation, capsule);
    List<String> ids = normalizeArguments(capsule.toToolCall().arguments());
    assertSafeResultBudget(ids);
    if (ids.isEmpty()) {
      return ToolResult.success(render(MemoryForgetResult.empty()));
    }
    var command =
        new MemoryForgetCommand(
            MemoryManagementRules.scope(capsule.sessionId()),
            operation.reference().value(),
            ids,
            MemoryManagementRules.forgetArgumentHash(ids),
            clock.instant());
    MemoryForgetResult result = Objects.requireNonNull(store.softForget(command), "forget result");
    if (!result.requestedIds().equals(ids)) {
      throw new IllegalStateException("Forget Store 返回了不匹配的请求 ID");
    }
    String safeResult = render(result);
    if (safeResult.length() > MAX_SAFE_RESULT_CHARACTERS) {
      throw new IllegalStateException("Forget 安全结果超出 Ledger 上限");
    }
    return ToolResult.success(safeResult);
  }

  private static void requireBound(PendingOperation operation, PendingOperationCapsule capsule) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capsule, "capsule");
    if (!capsule.matches(operation)
        || !TOOL_NAME.equals(operation.approval().toolName())
        || !TOOL_VERSION.equals(operation.approval().toolVersion())
        || operation.approval().risk() != ToolRisk.WRITE
        || !EXECUTION_BOUNDARY_VERSION.equals(capsule.executionBoundaryVersion())) {
      throw new IllegalArgumentException("Memory Forget Capability 绑定不匹配");
    }
  }

  static List<String> normalizeArguments(Map<String, Object> arguments) {
    Objects.requireNonNull(arguments, "arguments");
    if (arguments.size() != 1 || !arguments.containsKey("ids")) {
      throw new IllegalArgumentException("Forget 参数必须只包含 ids");
    }
    Object rawIds = arguments.get("ids");
    if (!(rawIds instanceof List<?> values)) {
      throw new IllegalArgumentException("Forget ids 必须是数组");
    }
    var strings = new ArrayList<String>(values.size());
    for (Object value : values) {
      if (!(value instanceof String id)) {
        throw new IllegalArgumentException("Forget ids 必须都是字符串");
      }
      strings.add(id);
    }
    return MemoryForgetCommand.normalizeIds(strings);
  }

  static String canonicalArgumentsJson(List<String> ids) {
    Objects.requireNonNull(ids, "ids");
    List<String> normalized = MemoryForgetCommand.normalizeIds(ids);
    if (!normalized.equals(ids)) {
      throw new IllegalArgumentException("Forget 参数必须已完成规范化和稳定去重");
    }
    return "{\"ids\":" + renderIds(normalized) + "}";
  }

  static ToolResult immediateSuccess() {
    return ToolResult.success(render(MemoryForgetResult.empty()));
  }

  static void requireSafeResultBudget(List<String> ids) {
    assertSafeResultBudget(ids);
  }

  private static void assertSafeResultBudget(List<String> ids) {
    String maximumSafeProjection = render(new MemoryForgetResult(ids, List.of(), ids));
    if (maximumSafeProjection.length() > MAX_SAFE_RESULT_CHARACTERS) {
      throw new IllegalArgumentException("Forget 参数产生的安全结果超出上限");
    }
  }

  private static String render(MemoryForgetResult result) {
    return "{\"requested_ids\":"
        + renderIds(result.requestedIds())
        + ",\"superseded_ids\":"
        + renderIds(result.supersededIds())
        + ",\"missing_ids\":"
        + renderIds(result.missingIds())
        + ",\"count\":"
        + result.count()
        + "}";
  }

  private static String renderIds(List<String> ids) {
    var result = new StringBuilder("[");
    for (int index = 0; index < ids.size(); index++) {
      if (index > 0) {
        result.append(',');
      }
      appendJsonString(result, ids.get(index));
    }
    return result.append(']').toString();
  }

  private static void appendJsonString(StringBuilder destination, String value) {
    destination.append('"');
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '"' -> destination.append("\\\"");
        case '\\' -> destination.append("\\\\");
        case '\b' -> destination.append("\\b");
        case '\f' -> destination.append("\\f");
        case '\n' -> destination.append("\\n");
        case '\r' -> destination.append("\\r");
        case '\t' -> destination.append("\\t");
        default -> {
          if (current < 0x20) {
            destination.append(String.format("\\u%04x", (int) current));
          } else {
            destination.append(current);
          }
        }
      }
    }
    destination.append('"');
  }
}
