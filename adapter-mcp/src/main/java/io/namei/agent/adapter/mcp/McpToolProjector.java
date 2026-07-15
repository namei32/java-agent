package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

final class McpToolProjector {
  static final int MAX_DESCRIPTION_BYTES = 4_096;
  private static final int MAX_REMOTE_NAME_BYTES = 512;

  private final McpSchemaProjector schemaProjector;

  McpToolProjector(int maxSchemaBytes) {
    this.schemaProjector = new McpSchemaProjector(maxSchemaBytes);
  }

  Optional<McpProjectedTool> project(McpServerDefinition server, McpRemoteTool remote) {
    if (server == null || remote == null || !validRemoteName(remote.name())) {
      return Optional.empty();
    }
    McpToolPolicy policy = server.tools().get(remote.name());
    if (policy == null || !policy.enabled() || policy.risk() != ToolRisk.READ_ONLY) {
      return Optional.empty();
    }
    String description = safeDescription(remote.description());
    if (description == null) {
      return Optional.empty();
    }
    Optional<Map<String, Object>> schema = schemaProjector.project(remote.inputSchema());
    if (schema.isEmpty()) {
      return Optional.empty();
    }
    try {
      ToolDefinition definition =
          new ToolDefinition(
              McpToolNameMapper.map(server.id(), remote.name()),
              description,
              schema.orElseThrow(),
              ToolRisk.READ_ONLY);
      return Optional.of(new McpProjectedTool(remote.name(), definition));
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private static boolean validRemoteName(String name) {
    return name != null
        && !name.isBlank()
        && name.getBytes(StandardCharsets.UTF_8).length <= MAX_REMOTE_NAME_BYTES
        && name.codePoints().noneMatch(Character::isISOControl);
  }

  private static String safeDescription(String description) {
    if (description == null) {
      return null;
    }
    String stripped = description.strip();
    if (stripped.isBlank()
        || stripped.getBytes(StandardCharsets.UTF_8).length > MAX_DESCRIPTION_BYTES
        || stripped.codePoints().anyMatch(Character::isISOControl)) {
      return null;
    }
    return stripped;
  }
}
