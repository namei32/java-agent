package io.namei.agent.kernel.channel;

public record OutboundMessage(
    int schemaVersion,
    String turnId,
    String sessionId,
    MessageRoute route,
    long sequence,
    OutboundMessageType type,
    String content,
    String code,
    boolean retryable) {
  public OutboundMessage {
    MessageContract.requireCurrentVersion(schemaVersion);
    turnId =
        MessageContract.identifier(turnId, "turnId", MessageContract.MAX_TURN_ID_CHARACTERS);
    sessionId =
        MessageContract.identifier(
            sessionId, "sessionId", MessageContract.MAX_SESSION_ID_CHARACTERS);
    if (route == null) {
      throw new IllegalArgumentException("route 不能为空");
    }
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence 不能为负数");
    }
    if (type == null) {
      throw new IllegalArgumentException("type 不能为空");
    }
    content = MessageContract.outboundContent(content);
    code = code == null ? "" : code.strip();
    validatePayload(sequence, type, content, code, retryable);
  }

  public static OutboundMessage started(String turnId, String sessionId, MessageRoute route) {
    return new OutboundMessage(
        MessageContract.CURRENT_VERSION,
        turnId,
        sessionId,
        route,
        0,
        OutboundMessageType.TURN_STARTED,
        "",
        "",
        false);
  }

  public static OutboundMessage delta(
      String turnId, String sessionId, MessageRoute route, long sequence, String content) {
    return new OutboundMessage(
        MessageContract.CURRENT_VERSION,
        turnId,
        sessionId,
        route,
        sequence,
        OutboundMessageType.CONTENT_DELTA,
        content,
        "",
        false);
  }

  public static OutboundMessage completed(
      String turnId, String sessionId, MessageRoute route, long sequence, String content) {
    return new OutboundMessage(
        MessageContract.CURRENT_VERSION,
        turnId,
        sessionId,
        route,
        sequence,
        OutboundMessageType.TURN_COMPLETED,
        content,
        "",
        false);
  }

  public static OutboundMessage cancelled(
      String turnId,
      String sessionId,
      MessageRoute route,
      long sequence,
      TurnCancellationCode code) {
    if (code == null) {
      throw new IllegalArgumentException("取消码不能为空");
    }
    return new OutboundMessage(
        MessageContract.CURRENT_VERSION,
        turnId,
        sessionId,
        route,
        sequence,
        OutboundMessageType.TURN_CANCELLED,
        "",
        code.name(),
        false);
  }

  public static OutboundMessage failed(
      String turnId,
      String sessionId,
      MessageRoute route,
      long sequence,
      TurnFailureCode code) {
    if (code == null) {
      throw new IllegalArgumentException("失败码不能为空");
    }
    return new OutboundMessage(
        MessageContract.CURRENT_VERSION,
        turnId,
        sessionId,
        route,
        sequence,
        OutboundMessageType.TURN_FAILED,
        "",
        code.name(),
        code.retryable());
  }

  private static void validatePayload(
      long sequence,
      OutboundMessageType type,
      String content,
      String code,
      boolean retryable) {
    switch (type) {
      case TURN_STARTED -> {
        requireSequence(sequence, 0, "Started");
        requireEmpty(content, "Started content");
        requireEmpty(code, "Started code");
        requireNotRetryable(retryable);
      }
      case CONTENT_DELTA -> {
        requirePositiveSequence(sequence);
        if (content.isEmpty()) {
          throw new IllegalArgumentException("Delta content 不能为空");
        }
        requireEmpty(code, "Delta code");
        requireNotRetryable(retryable);
      }
      case TURN_COMPLETED -> {
        requirePositiveSequence(sequence);
        if (content.isBlank()) {
          throw new IllegalArgumentException("Completed content 不能为空");
        }
        requireEmpty(code, "Completed code");
        requireNotRetryable(retryable);
      }
      case TURN_CANCELLED -> {
        requirePositiveSequence(sequence);
        requireEmpty(content, "Cancelled content");
        TurnCancellationCode.parse(code);
        requireNotRetryable(retryable);
      }
      case TURN_FAILED -> {
        requirePositiveSequence(sequence);
        requireEmpty(content, "Failed content");
        TurnFailureCode failure = TurnFailureCode.parse(code);
        if (retryable != failure.retryable()) {
          throw new IllegalArgumentException("retryable 与失败码不一致");
        }
      }
    }
  }

  private static void requirePositiveSequence(long sequence) {
    if (sequence < 1) {
      throw new IllegalArgumentException("Started 之后的 sequence 必须为正数");
    }
  }

  private static void requireSequence(long actual, long expected, String field) {
    if (actual != expected) {
      throw new IllegalArgumentException(field + " sequence 无效");
    }
  }

  private static void requireEmpty(String value, String field) {
    if (!value.isEmpty()) {
      throw new IllegalArgumentException(field + " 必须为空");
    }
  }

  private static void requireNotRetryable(boolean retryable) {
    if (retryable) {
      throw new IllegalArgumentException("非失败消息不能设置 retryable");
    }
  }

  @Override
  public String toString() {
    return "OutboundMessage[schemaVersion="
        + schemaVersion
        + ", sequence="
        + sequence
        + ", type="
        + type
        + ", code="
        + code
        + ", sensitiveFields=<redacted>]";
  }
}
