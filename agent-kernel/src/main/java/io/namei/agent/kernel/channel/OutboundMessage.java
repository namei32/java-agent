package io.namei.agent.kernel.channel;

/**
 * 表示 Agent 发往外部渠道的有序消息事件。
 *
 * <p>同一轮次必须先发送序号为 0 的 Started，随后使用正数序号发送增量和唯一终态。构造器会校验消息类型与 content、code、retryable
 * 之间的组合，避免渠道适配器收到模糊状态。
 *
 * @param schemaVersion 消息协议版本
 * @param turnId 所属 Agent 轮次
 * @param sessionId 所属内部会话
 * @param route 渠道投递目标
 * @param sequence 轮次内严格递增的序号
 * @param type 消息事件类型
 * @param content 文本载荷，仅增量和完成事件允许携带
 * @param code 取消或失败的稳定代码
 * @param retryable 失败是否适合重试，必须与失败代码定义一致
 */
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
    turnId = MessageContract.identifier(turnId, "turnId", MessageContract.MAX_TURN_ID_CHARACTERS);
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

  /** 创建轮次开始事件；它固定使用序号 0 且不携带正文。 */
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

  /** 创建一段流式正文增量；调用方负责提供严格递增的正数序号。 */
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

  /** 创建成功终态；正文必须非空，发送后不得再产生该轮次事件。 */
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

  /** 创建取消终态，并把内部取消原因投影为稳定渠道代码。 */
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

  /** 创建失败终态；可重试性由 {@link TurnFailureCode} 决定。 */
  public static OutboundMessage failed(
      String turnId, String sessionId, MessageRoute route, long sequence, TurnFailureCode code) {
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
      long sequence, OutboundMessageType type, String content, String code, boolean retryable) {
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
