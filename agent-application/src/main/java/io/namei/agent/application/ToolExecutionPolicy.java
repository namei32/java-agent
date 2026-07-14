package io.namei.agent.application;

import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolRisk;
import java.util.Objects;

@FunctionalInterface
interface ToolExecutionPolicy {
  ToolRisk classify(ToolDefinition definition, ToolCall call);

  static ToolExecutionPolicy registeredRisk() {
    return (definition, call) -> definition.risk();
  }

  static ToolRisk effectiveRisk(
      ToolDefinition definition, ToolCall call, ToolExecutionPolicy policy) {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(policy, "policy");
    ToolRisk classified = Objects.requireNonNull(policy.classify(definition, call), "policy risk");
    return classified.ordinal() > definition.risk().ordinal() ? classified : definition.risk();
  }
}
