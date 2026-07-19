package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import java.util.List;

/**
 * Static Catalog surface for the one approved pending-only {@code forget_memory} capability.
 *
 * <p>The placeholder deliberately cannot execute through the ordinary Tool Registry. A later
 * Turn-bound producer intercepts it before generic side-effect coordination and creates a durable
 * local approval request instead.
 */
public final class MemoryForgetPendingToolset {
  private static final String PENDING_ASSISTANT_PROJECTION = "记忆遗忘请求正在等待本机审批。";
  private static final MemoryForgetPendingToolset DISABLED =
      new MemoryForgetPendingToolset(List.of(), MemoryForgetPendingProducer.disabled());
  private static final MemoryForgetPendingToolset ENABLED =
      new MemoryForgetPendingToolset(
          List.of(new PendingTool()), MemoryForgetPendingProducer.disabled());

  private final List<Tool> tools;
  private final MemoryForgetPendingProducer producer;

  private MemoryForgetPendingToolset(List<Tool> tools, MemoryForgetPendingProducer producer) {
    this.tools = List.copyOf(tools);
    this.producer = java.util.Objects.requireNonNull(producer, "producer");
  }

  public static MemoryForgetPendingToolset disabled() {
    return DISABLED;
  }

  /** Returns only the static Tool surface; it does not authorize or construct a producer. */
  public static MemoryForgetPendingToolset catalogOnly() {
    return ENABLED;
  }

  public static MemoryForgetPendingToolset enabled(MemoryForgetPendingService service) {
    return new MemoryForgetPendingToolset(
        List.of(new PendingTool()), MemoryForgetPendingProducer.enabled(service));
  }

  public static ToolDefinition definition() {
    return MemoryForgetCapability.definition();
  }

  public static String pendingAssistantProjection() {
    return PENDING_ASSISTANT_PROJECTION;
  }

  public List<Tool> tools() {
    return tools;
  }

  MemoryForgetPendingProducer producer() {
    return producer;
  }

  private static final class PendingTool implements Tool {
    @Override
    public ToolDefinition definition() {
      return MemoryForgetPendingToolset.definition();
    }

    @Override
    public ToolResult execute(java.util.Map<String, Object> arguments) {
      return ToolResult.error("工具不可用。");
    }
  }
}
