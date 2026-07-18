package io.namei.agent.application;

import io.namei.agent.kernel.tool.ToolRisk;
import java.util.Objects;

record ToolCatalogMatch(
    String name, String summary, ToolRisk risk, ToolCatalogSource source, boolean alwaysOn) {
  ToolCatalogMatch {
    name = required(name, "工具名称");
    summary = required(summary, "工具摘要");
    risk = Objects.requireNonNull(risk, "risk");
    source = Objects.requireNonNull(source, "source");
  }

  private static String required(String value, String field) {
    String normalized = Objects.requireNonNull(value, field).strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return normalized;
  }
}
