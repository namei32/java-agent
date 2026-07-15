package io.namei.agent.adapter.mcp;

import io.namei.agent.kernel.tool.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class McpCatalogFingerprint {
  private McpCatalogFingerprint() {}

  static String of(List<McpProjectedTool> tools) {
    MessageDigest digest = sha256();
    tools.stream()
        .sorted(Comparator.comparing(McpProjectedTool::remoteName))
        .forEach(
            tool -> {
              ToolDefinition definition = tool.definition();
              scalar(digest, "remote", tool.remoteName());
              scalar(digest, "local", definition.name());
              scalar(digest, "description", definition.description());
              scalar(digest, "risk", definition.risk().name());
              value(digest, definition.inputSchema());
            });
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void value(MessageDigest digest, Object value) {
    if (value instanceof Map<?, ?> map) {
      marker(digest, "map", map.size());
      map.entrySet().stream()
          .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
          .forEach(
              entry -> {
                scalar(digest, "key", String.valueOf(entry.getKey()));
                value(digest, entry.getValue());
              });
      return;
    }
    if (value instanceof List<?> list) {
      marker(digest, "list", list.size());
      list.forEach(item -> value(digest, item));
      return;
    }
    scalar(
        digest,
        value == null ? "null" : value.getClass().getName(),
        value == null ? "" : String.valueOf(value));
  }

  private static void marker(MessageDigest digest, String type, int size) {
    scalar(digest, type, Integer.toString(size));
  }

  private static void scalar(MessageDigest digest, String type, String value) {
    byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(intBytes(typeBytes.length));
    digest.update(typeBytes);
    digest.update(intBytes(valueBytes.length));
    digest.update(valueBytes);
  }

  private static byte[] intBytes(int value) {
    return new byte[] {
      (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
    };
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }
}
