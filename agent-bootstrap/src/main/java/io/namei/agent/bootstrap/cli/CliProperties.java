package io.namei.agent.bootstrap.cli;

import io.namei.agent.application.BoundedOutboundBuffer;
import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.MessageRoute;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.cli")
public record CliProperties(
    @DefaultValue("cli:local") String sessionId,
    @DefaultValue("local") String conversationId,
    @DefaultValue("32") int bufferCapacity,
    @DefaultValue("2s") Duration publishTimeout,
    @DefaultValue("100ms") Duration pollTimeout) {
  public CliProperties {
    sessionId =
        identifier(sessionId, "agent.cli.session-id", MessageContract.MAX_SESSION_ID_CHARACTERS);
    conversationId = new MessageRoute("cli", conversationId).conversationId();
    if (bufferCapacity < 1 || bufferCapacity > BoundedOutboundBuffer.MAX_CAPACITY) {
      throw new IllegalArgumentException(
          "agent.cli.buffer-capacity 必须在 1.." + BoundedOutboundBuffer.MAX_CAPACITY + " 之间");
    }
    requirePositiveBounded(publishTimeout, "agent.cli.publish-timeout");
    requirePositiveBounded(pollTimeout, "agent.cli.poll-timeout");
  }

  private static String identifier(String value, String field, int maxCodePoints) {
    if (value == null) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
      throw new IllegalArgumentException(field + " 超过长度上限");
    }
    if (normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " 不能包含控制字符");
    }
    return normalized;
  }

  private static void requirePositiveBounded(Duration duration, String field) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(field + " 必须为正数");
    }
    if (duration.compareTo(BoundedOutboundBuffer.MAX_WAIT) > 0) {
      throw new IllegalArgumentException(field + " 不能超过 " + BoundedOutboundBuffer.MAX_WAIT);
    }
  }
}
