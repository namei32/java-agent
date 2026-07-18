package io.namei.agent.bootstrap.plugin;

import io.namei.agent.application.OutboundMessageObserver;
import io.namei.agent.application.plugin.PluginTapDispatcher;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageType;
import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginTapEvent;
import io.namei.agent.kernel.plugin.PluginTapOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** 在权威 Message Sink 成功后，仅投影脱敏的终端消息元数据。 */
final class PluginOutboundMessageObserver implements OutboundMessageObserver {
  private final PluginTapDispatcher dispatcher;

  PluginOutboundMessageObserver(PluginTapDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public void onTerminal(OutboundMessage message) {
    Objects.requireNonNull(message, "message");
    try {
      dispatcher.publish(
          new PluginTapEvent(
              PluginCapability.TURN_TAP, hash(message), outcome(message.type()), null, 0));
    } catch (RuntimeException ignored) {
      // Plugin 观察不能逆转 Message 的既有成功投递。
    }
  }

  private static PluginTapOutcome outcome(OutboundMessageType type) {
    return switch (type) {
      case TURN_COMPLETED -> PluginTapOutcome.COMPLETED;
      case TURN_FAILED -> PluginTapOutcome.FAILED;
      case TURN_CANCELLED -> PluginTapOutcome.SKIPPED;
      case TURN_STARTED, CONTENT_DELTA -> PluginTapOutcome.ACCEPTED;
    };
  }

  private static String hash(OutboundMessage message) {
    try {
      String safeProjection =
          "plugin-message-v1\u0000"
              + message.turnId()
              + '\u0000'
              + message.sessionId()
              + '\u0000'
              + message.route().channel()
              + '\u0000'
              + message.route().conversationId()
              + '\u0000'
              + message.sequence()
              + '\u0000'
              + message.type().name();
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(safeProjection.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 不可用", unavailable);
    }
  }
}
