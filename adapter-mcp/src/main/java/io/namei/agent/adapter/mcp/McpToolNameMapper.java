package io.namei.agent.adapter.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class McpToolNameMapper {
  private static final Pattern SERVER_ID = Pattern.compile("[a-z][a-z0-9_-]{0,15}");
  private static final int MAX_LOCAL_NAME_LENGTH = 64;
  private static final int HASH_LENGTH = 10;

  private McpToolNameMapper() {}

  public static String map(String serverId, String remoteName) {
    if (serverId == null
        || !SERVER_ID.matcher(serverId).matches()
        || remoteName == null
        || remoteName.isBlank()) {
      throw new IllegalArgumentException("MCP Tool 名称无效");
    }

    StringBuilder normalized = new StringBuilder(remoteName.length());
    boolean replaced = false;
    for (int offset = 0; offset < remoteName.length(); ) {
      int codePoint = remoteName.codePointAt(offset);
      if (isLocalNameCodePoint(codePoint)) {
        normalized.appendCodePoint(codePoint);
      } else {
        normalized.append('_');
        replaced = true;
      }
      offset += Character.charCount(codePoint);
    }

    String candidate = "mcp_" + serverId + "__" + normalized;
    if (!replaced && candidate.length() <= MAX_LOCAL_NAME_LENGTH) {
      return candidate;
    }

    String suffix = "_" + hashPrefix(serverId, remoteName);
    int prefixLength = Math.min(candidate.length(), MAX_LOCAL_NAME_LENGTH - suffix.length());
    return candidate.substring(0, prefixLength) + suffix;
  }

  private static boolean isLocalNameCodePoint(int codePoint) {
    return (codePoint >= 'a' && codePoint <= 'z')
        || (codePoint >= 'A' && codePoint <= 'Z')
        || (codePoint >= '0' && codePoint <= '9')
        || codePoint == '_'
        || codePoint == '-';
  }

  private static String hashPrefix(String serverId, String remoteName) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(serverId.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(remoteName.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest()).substring(0, HASH_LENGTH);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }
}
