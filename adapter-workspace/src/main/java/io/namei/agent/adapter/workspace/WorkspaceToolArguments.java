package io.namei.agent.adapter.workspace;

import java.util.Map;
import java.util.Set;

/** 独立于模型 Schema 校验器，对直接 Tool 调用进行防御性解析。 */
final class WorkspaceToolArguments {
  private WorkspaceToolArguments() {}

  static WorkspaceToolPath requiredPath(Map<String, Object> arguments, Set<String> allowed) {
    requireOnly(arguments, allowed);
    Object value = arguments.get("path");
    if (!(value instanceof String path)) {
      throw unavailable();
    }
    return WorkspaceToolPath.parse(path);
  }

  static int optionalInteger(
      Map<String, Object> arguments, String name, int defaultValue, int minimum) {
    if (!arguments.containsKey(name)) {
      return defaultValue;
    }
    Object value = arguments.get(name);
    if (!(value instanceof Number number)) {
      throw unavailable();
    }
    double numeric = number.doubleValue();
    if (!Double.isFinite(numeric)
        || numeric != Math.rint(numeric)
        || numeric < minimum
        || numeric > Integer.MAX_VALUE) {
      throw unavailable();
    }
    return (int) numeric;
  }

  private static void requireOnly(Map<String, Object> arguments, Set<String> allowed) {
    if (arguments == null || !allowed.containsAll(arguments.keySet())) {
      throw unavailable();
    }
  }

  private static WorkspaceToolContractException unavailable() {
    return WorkspaceToolError.WORKSPACE_TOOL_UNAVAILABLE.violation();
  }
}
