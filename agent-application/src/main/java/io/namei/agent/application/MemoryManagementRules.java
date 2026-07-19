package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemoryType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class MemoryManagementRules {
  private static final String MUTATION_VERSION = "java-memory-mutation-v1";
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]+");

  private MemoryManagementRules() {}

  static MemoryScope scope(String sessionId) {
    String normalized = safeIdentifier(sessionId, "Session ID");
    return new MemoryScope(sha256(utf8(normalized)));
  }

  static String requestId(String requestId) {
    return safeIdentifier(requestId, "Request ID");
  }

  static String itemId(String itemId) {
    return safeIdentifier(itemId, "Memory Item ID");
  }

  static String content(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Memory Content 不能为空");
    }
    String stripped = value.strip();
    if (stripped.isBlank()) {
      throw new IllegalArgumentException("Memory Content 不能为空");
    }
    if (stripped.length() > 4000) {
      throw new IllegalArgumentException("Memory Content 超过长度上限");
    }

    var normalized = new StringBuilder(stripped.length());
    boolean whitespace = false;
    var points = stripped.codePoints().iterator();
    while (points.hasNext()) {
      int point = points.nextInt();
      if (Character.isWhitespace(point)) {
        whitespace = true;
      } else {
        if (whitespace && !normalized.isEmpty()) {
          normalized.append(' ');
        }
        normalized.appendCodePoint(point);
        whitespace = false;
      }
    }
    return normalized.toString();
  }

  static String contentHash(String content) {
    return sha256(utf8(content));
  }

  static String writeArgumentHash(
      MemoryType type, String contentHash, int emotionalWeight, Instant happenedAt) {
    Objects.requireNonNull(type, "type");
    return argumentHash(
        MUTATION_VERSION,
        "UPSERT",
        type.name(),
        contentHash,
        Integer.toString(emotionalWeight),
        happenedAt == null ? "" : happenedAt.toString());
  }

  static String deleteArgumentHash(String itemId) {
    return argumentHash(MUTATION_VERSION, "DELETE", "", itemId, "", "");
  }

  static String forgetArgumentHash(List<String> ids) {
    Objects.requireNonNull(ids, "ids");
    var fields = new ArrayList<String>(ids.size() + 3);
    fields.add(MUTATION_VERSION);
    fields.add("FORGET");
    fields.add(Integer.toString(ids.size()));
    ids.forEach(id -> fields.add(Objects.requireNonNull(id, "Memory Item ID")));
    return argumentHash(fields.toArray(String[]::new));
  }

  private static String safeIdentifier(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(field + " 超过长度上限");
    }
    if (!SAFE_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " 格式无效");
    }
    return value;
  }

  private static String argumentHash(String... fields) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        for (String field : fields) {
          byte[] encoded = utf8(Objects.requireNonNull(field, "mutation field"));
          output.writeInt(encoded.length);
          output.write(encoded);
        }
      }
      return sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("无法生成 Memory Argument Hash", exception);
    }
  }

  private static byte[] utf8(String value) {
    try {
      var encoder = StandardCharsets.UTF_8.newEncoder();
      encoder.onMalformedInput(CodingErrorAction.REPORT);
      encoder.onUnmappableCharacter(CodingErrorAction.REPORT);
      var buffer = encoder.encode(java.nio.CharBuffer.wrap(value));
      var encoded = new byte[buffer.remaining()];
      buffer.get(encoded);
      return encoded;
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Memory 字段包含无效 Unicode");
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK 缺少 SHA-256", exception);
    }
  }
}
