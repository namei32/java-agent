package io.namei.agent.application;

import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ToolCatalogEntry(
    Tool tool,
    ToolDefinition definition,
    ToolCatalogVisibility visibility,
    ToolCatalogSource source,
    String sourceId,
    List<String> searchHints) {
  public ToolCatalogEntry(
      Tool tool,
      ToolCatalogVisibility visibility,
      ToolCatalogSource source,
      String sourceId,
      List<String> searchHints) {
    this(tool, snapshot(tool), visibility, source, sourceId, searchHints);
  }

  public ToolCatalogEntry {
    tool = Objects.requireNonNull(tool, "tool");
    definition = Objects.requireNonNull(definition, "definition");
    visibility = Objects.requireNonNull(visibility, "visibility");
    source = Objects.requireNonNull(source, "source");
    sourceId = Objects.requireNonNullElse(sourceId, "").strip();
    if (source == ToolCatalogSource.BUILTIN && !sourceId.isEmpty()) {
      throw new IllegalArgumentException("内置工具不能提供来源标识");
    }
    if (source == ToolCatalogSource.MCP && sourceId.isBlank()) {
      throw new IllegalArgumentException("MCP 来源必须提供来源标识");
    }
    if (sourceId.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("工具来源标识不能包含控制字符");
    }
    Objects.requireNonNull(searchHints, "searchHints");
    var normalizedHints = new ArrayList<String>(searchHints.size());
    for (String hint : searchHints) {
      String normalized = Objects.requireNonNull(hint, "searchHint").strip();
      if (normalized.isBlank() || normalized.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("工具搜索提示无效");
      }
      normalizedHints.add(normalized);
    }
    searchHints = List.copyOf(normalizedHints);
  }

  private static ToolDefinition snapshot(Tool tool) {
    return Objects.requireNonNull(
        Objects.requireNonNull(tool, "tool").definition(), "tool.definition");
  }
}
