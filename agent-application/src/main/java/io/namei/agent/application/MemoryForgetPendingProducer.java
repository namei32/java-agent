package io.namei.agent.application;

import io.namei.agent.kernel.tool.ToolCall;
import java.util.Objects;

/**
 * Narrow adapter from an authenticated Chat turn to the already durable pending-operation service.
 *
 * <p>It has no generic Tool execution, recovery, or Memory mutation authority.
 */
final class MemoryForgetPendingProducer {
  private static final MemoryForgetPendingProducer DISABLED = new MemoryForgetPendingProducer(null);

  private final MemoryForgetPendingService service;

  private MemoryForgetPendingProducer(MemoryForgetPendingService service) {
    this.service = service;
  }

  static MemoryForgetPendingProducer disabled() {
    return DISABLED;
  }

  static MemoryForgetPendingProducer enabled(MemoryForgetPendingService service) {
    return new MemoryForgetPendingProducer(Objects.requireNonNull(service, "service"));
  }

  boolean isEnabled() {
    return service != null;
  }

  boolean owns(ToolCall call) {
    return isEnabled() && MemoryForgetCapability.TOOL_NAME.equals(call.name());
  }

  MemoryForgetPendingOutcome create(MemoryForgetPendingTurnContext context, ToolCall call) {
    if (!owns(Objects.requireNonNull(call, "call"))) {
      throw new IllegalStateException("Memory Forget Pending Producer 不可用或 Tool 不匹配");
    }
    return service.create(Objects.requireNonNull(context, "context").request(call));
  }
}
