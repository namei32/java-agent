package io.namei.agent.application;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import java.util.Objects;

/**
 * 渠道适配器提交给可靠入站协调器的封闭事件协议。
 *
 * <p>{@link Accepted} 携带通过鉴权的用户消息，{@link Ignored} 持久记录被过滤事件，{@link Control} 表示取消等控制动作。公共字段用于外部事件去重和
 * Cursor 推进。
 */
public sealed interface ReliableInboundEvent
    permits ReliableInboundEvent.Accepted,
        ReliableInboundEvent.Ignored,
        ReliableInboundEvent.Control {
  ChannelInstanceId instance();

  String externalEventId();

  long externalSequence();

  static Accepted accepted(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      String targetId,
      InboundMessage inbound) {
    return new Accepted(instance, externalEventId, externalSequence, targetId, inbound);
  }

  static Ignored ignored(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      String decisionCode) {
    return new Ignored(instance, externalEventId, externalSequence, decisionCode);
  }

  static Control control(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      String decisionCode,
      String targetId,
      String targetSessionId) {
    return new Control(
        instance, externalEventId, externalSequence, decisionCode, targetId, targetSessionId);
  }

  /** 已鉴权、可以创建 Agent Turn 的普通入站消息。 */
  record Accepted(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      String targetId,
      InboundMessage inbound)
      implements ReliableInboundEvent {
    public Accepted {
      requireCommon(instance, externalEventId, externalSequence);
      targetId = requireIdentifier(targetId, "targetId", 256);
      Objects.requireNonNull(inbound, "inbound");
      if (!instance.channel().equals(inbound.route().channel())) {
        throw new IllegalArgumentException("入站 Route 与渠道实例不一致");
      }
    }

    @Override
    public String toString() {
      return "ReliableInboundEvent.Accepted[sensitiveFields=<redacted>]";
    }
  }

  /** 已被白名单、内容或协议规则过滤，但仍需要推进外部 Cursor 的事件。 */
  record Ignored(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      String decisionCode)
      implements ReliableInboundEvent {
    public Ignored {
      requireCommon(instance, externalEventId, externalSequence);
      decisionCode = requireIdentifier(decisionCode, "decisionCode", 64);
    }

    @Override
    public String toString() {
      return "ReliableInboundEvent.Ignored[sensitiveFields=<redacted>]";
    }
  }

  /** 指向某个活动会话或 Turn 的渠道控制事件。 */
  record Control(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      String decisionCode,
      String targetId,
      String targetSessionId)
      implements ReliableInboundEvent {
    public Control {
      requireCommon(instance, externalEventId, externalSequence);
      decisionCode = requireIdentifier(decisionCode, "decisionCode", 64);
      targetId = requireIdentifier(targetId, "targetId", 256);
      targetSessionId = requireIdentifier(targetSessionId, "targetSessionId", 128);
    }

    @Override
    public String toString() {
      return "ReliableInboundEvent.Control[sensitiveFields=<redacted>]";
    }
  }

  private static void requireCommon(
      ChannelInstanceId instance, String externalEventId, long externalSequence) {
    Objects.requireNonNull(instance, "instance");
    requireIdentifier(externalEventId, "externalEventId", 128);
    if (externalSequence < 0 || externalSequence == Long.MAX_VALUE) {
      throw new IllegalArgumentException("externalSequence 超出可靠处理范围");
    }
  }

  private static String requireIdentifier(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    String normalized = value.strip();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(field + " 过长");
    }
    return normalized;
  }
}
