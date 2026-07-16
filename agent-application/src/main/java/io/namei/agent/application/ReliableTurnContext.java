package io.namei.agent.application;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import java.util.Objects;

public record ReliableTurnContext(
    ChannelInstanceId instance, InboundMessage inbound, String targetId, long claimRevision) {
  public ReliableTurnContext {
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(inbound, "inbound");
    targetId = requireIdentifier(targetId, "targetId", 256);
    if (claimRevision < 0) {
      throw new IllegalArgumentException("Claim Revision 不能为负数");
    }
  }

  private static String requireIdentifier(String value, String field, int maximum) {
    Objects.requireNonNull(value, field);
    String normalized = value.strip();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " 无效");
    }
    if (normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " 不能包含控制字符");
    }
    return normalized;
  }

  @Override
  public String toString() {
    return "ReliableTurnContext[claimRevision=" + claimRevision + ", sensitiveFields=<redacted>]";
  }
}
