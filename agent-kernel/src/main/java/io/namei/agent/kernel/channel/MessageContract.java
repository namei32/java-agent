package io.namei.agent.kernel.channel;

import java.util.regex.Pattern;

public final class MessageContract {
  public static final int CURRENT_VERSION = 1;
  public static final int MAX_MESSAGE_ID_CHARACTERS = 128;
  public static final int MAX_TURN_ID_CHARACTERS = 128;
  public static final int MAX_SESSION_ID_CHARACTERS = 256;
  public static final int MAX_CHANNEL_CHARACTERS = 32;
  public static final int MAX_ROUTE_ID_CHARACTERS = 256;
  public static final int MAX_SENDER_ID_CHARACTERS = 256;
  public static final int MAX_CONTENT_CHARACTERS = 32_000;

  private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

  private MessageContract() {}

  static void requireCurrentVersion(int schemaVersion) {
    if (schemaVersion != CURRENT_VERSION) {
      throw new IllegalArgumentException("不支持的消息 Contract 版本");
    }
  }

  static String identifier(String value, String field, int maxCharacters) {
    if (value == null) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    if (characterCount(normalized) > maxCharacters) {
      throw new IllegalArgumentException(field + " 超过长度上限");
    }
    if (normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " 不能包含控制字符");
    }
    return normalized;
  }

  static String channel(String value) {
    String normalized = identifier(value, "channel", MAX_CHANNEL_CHARACTERS);
    if (!CHANNEL.matcher(normalized).matches()) {
      throw new IllegalArgumentException("channel 格式无效");
    }
    return normalized;
  }

  static String inboundContent(String value) {
    if (value == null) {
      throw new IllegalArgumentException("content 不能为空");
    }
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("content 不能为空");
    }
    requireContentLimit(normalized);
    return normalized;
  }

  static String outboundContent(String value) {
    String normalized = value == null ? "" : value;
    requireContentLimit(normalized);
    return normalized;
  }

  private static void requireContentLimit(String content) {
    if (characterCount(content) > MAX_CONTENT_CHARACTERS) {
      throw new IllegalArgumentException("content 超过长度上限");
    }
  }

  private static int characterCount(String value) {
    return value.codePointCount(0, value.length());
  }
}
