package io.namei.agent.adapter.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class McpProcessEnvironment {
  private McpProcessEnvironment() {}

  static void replace(
      ProcessBuilder processBuilder,
      List<String> allowedVariableNames,
      Map<String, String> parentEnvironment) {
    Objects.requireNonNull(processBuilder, "processBuilder");
    Objects.requireNonNull(allowedVariableNames, "allowedVariableNames");
    Objects.requireNonNull(parentEnvironment, "parentEnvironment");
    Map<String, String> child = processBuilder.environment();
    child.clear();
    for (String name : allowedVariableNames) {
      String value = parentEnvironment.get(name);
      if (value != null) {
        child.put(name, value);
      }
    }
  }
}
