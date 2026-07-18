package io.namei.agent.bootstrap.plugin;

import io.namei.agent.application.plugin.PluginTapDispatcher;
import io.namei.agent.kernel.lifecycle.TurnEventType;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginLifecycleProjection;
import io.namei.agent.kernel.plugin.PluginTapEvent;
import io.namei.agent.kernel.plugin.PluginTapOutcome;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** 将既有 Chat/Tool 生命周期映射为不含原始业务内容的观察型 Tap。 */
final class PluginLifecycleTapObserver implements TurnLifecycleObserver {
  private final PluginTapDispatcher dispatcher;

  PluginLifecycleTapObserver(PluginTapDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public void onEvent(TurnLifecycleEvent event) {
    Objects.requireNonNull(event, "event");
    publish(new PluginTapEvent(capability(event.type()), hash(event), outcome(event), null, 0));
    PluginLifecycleProjection.project(event).ifPresent(this::publish);
  }

  private void publish(PluginTapEvent event) {
    try {
      dispatcher.publish(event);
    } catch (RuntimeException ignored) {
      // Plugin 可观测性不能改变已存在的 Chat/Tool 业务边界。
    }
  }

  private static PluginCapability capability(TurnEventType type) {
    return switch (type) {
      case TOOL_CALL_STARTED,
          APPROVAL_REQUESTED,
          APPROVAL_RESOLVED,
          SIDE_EFFECT_STARTED,
          SIDE_EFFECT_COMPLETED,
          TOOL_CALL_COMPLETED ->
          PluginCapability.TOOL_TAP;
      default -> PluginCapability.TURN_TAP;
    };
  }

  private static PluginTapOutcome outcome(TurnLifecycleEvent event) {
    if (event.type() == TurnEventType.TURN_FAILED
        || event.status().contains("FAILED")
        || event.status().contains("ERROR")
        || event.status().contains("DENIED")) {
      return PluginTapOutcome.FAILED;
    }
    return switch (event.type()) {
      case MODEL_COMPLETED,
          APPROVAL_RESOLVED,
          SIDE_EFFECT_COMPLETED,
          TOOL_CALL_COMPLETED,
          TURN_COMMITTED ->
          PluginTapOutcome.COMPLETED;
      default -> PluginTapOutcome.ACCEPTED;
    };
  }

  private static String hash(TurnLifecycleEvent event) {
    try {
      String safeProjection =
          "plugin-lifecycle-v1\u0000"
              + event.type().name()
              + '\u0000'
              + event.iteration()
              + '\u0000'
              + event.callId()
              + '\u0000'
              + event.toolName()
              + '\u0000'
              + event.status();
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(safeProjection.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 不可用", unavailable);
    }
  }
}
