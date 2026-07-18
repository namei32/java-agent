package io.namei.agent.kernel.control;

import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageType;
import io.namei.agent.kernel.channel.TurnCancellationCode;
import io.namei.agent.kernel.channel.TurnFailureCode;
import java.util.Objects;

public record ControlEventProjection(
    int schemaVersion,
    ControlTurnRef turnRef,
    long sequence,
    OutboundMessageType type,
    String content,
    String code,
    boolean retryable) {

  public ControlEventProjection {
    ControlPlaneContract.requireCurrentVersion(schemaVersion);
    turnRef = Objects.requireNonNull(turnRef, "turnRef");
    if (sequence < 0) {
      throw new IllegalArgumentException("控制事件 sequence 不能为负数");
    }
    type = Objects.requireNonNull(type, "type");
    if (content == null
        || content.codePointCount(0, content.length()) > MessageContract.MAX_CONTENT_CHARACTERS) {
      throw new IllegalArgumentException("控制事件 content 无效");
    }
    if (code == null || !code.equals(code.strip())) {
      throw new IllegalArgumentException("控制事件 code 无效");
    }
    validate(sequence, type, content, code, retryable);
  }

  public static ControlEventProjection from(ControlTurnRef turnRef, OutboundMessage message) {
    Objects.requireNonNull(message, "message");
    return new ControlEventProjection(
        ControlPlaneContract.CURRENT_VERSION,
        turnRef,
        message.sequence(),
        message.type(),
        message.content(),
        message.code(),
        message.retryable());
  }

  private static void validate(
      long sequence, OutboundMessageType type, String content, String code, boolean retryable) {
    switch (type) {
      case TURN_STARTED -> {
        if (sequence != 0 || !content.isEmpty() || !code.isEmpty() || retryable) {
          throw new IllegalArgumentException("Started 控制事件无效");
        }
      }
      case CONTENT_DELTA -> {
        requirePositive(sequence);
        if (content.isEmpty() || !code.isEmpty() || retryable) {
          throw new IllegalArgumentException("Delta 控制事件无效");
        }
      }
      case TURN_COMPLETED -> {
        requirePositive(sequence);
        if (content.isBlank() || !code.isEmpty() || retryable) {
          throw new IllegalArgumentException("Completed 控制事件无效");
        }
      }
      case TURN_CANCELLED -> {
        requirePositive(sequence);
        cancellationCode(code);
        if (!content.isEmpty() || retryable) {
          throw new IllegalArgumentException("Cancelled 控制事件无效");
        }
      }
      case TURN_FAILED -> {
        requirePositive(sequence);
        TurnFailureCode failure = failureCode(code);
        if (!content.isEmpty() || retryable != failure.retryable()) {
          throw new IllegalArgumentException("Failed 控制事件无效");
        }
      }
    }
  }

  private static void requirePositive(long sequence) {
    if (sequence < 1) {
      throw new IllegalArgumentException("Started 之后的控制事件 sequence 必须为正数");
    }
  }

  private static TurnCancellationCode cancellationCode(String code) {
    try {
      return TurnCancellationCode.valueOf(code);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("未知 Turn 取消码");
    }
  }

  private static TurnFailureCode failureCode(String code) {
    try {
      return TurnFailureCode.valueOf(code);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("未知 Turn 失败码");
    }
  }

  @Override
  public String toString() {
    return "ControlEventProjection[schemaVersion="
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
