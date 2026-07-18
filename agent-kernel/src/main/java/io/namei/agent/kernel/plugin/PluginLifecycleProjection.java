package io.namei.agent.kernel.plugin;

import io.namei.agent.kernel.lifecycle.TurnEventType;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Maps only supported Java lifecycle events to API v2 observation events. */
public final class PluginLifecycleProjection {
  private PluginLifecycleProjection() {}

  public static Optional<PluginTapEvent> project(TurnLifecycleEvent event) {
    Objects.requireNonNull(event, "event");
    PluginLifecyclePhase phase = phase(event.type());
    if (phase == PluginLifecyclePhase.UNSPECIFIED) {
      return Optional.empty();
    }
    return Optional.of(
        new PluginTapEvent(
            PluginCapability.LIFECYCLE_TAP, phase, hash(event, phase), outcome(event), null, 0));
  }

  private static PluginLifecyclePhase phase(TurnEventType type) {
    return switch (type) {
      case TURN_STARTED -> PluginLifecyclePhase.BEFORE_TURN;
      case MODEL_REQUESTED -> PluginLifecyclePhase.BEFORE_REASONING;
      case MODEL_COMPLETED -> PluginLifecyclePhase.AFTER_REASONING;
      case TOOL_CALL_STARTED -> PluginLifecyclePhase.BEFORE_TOOL_CALL;
      case TOOL_CALL_COMPLETED -> PluginLifecyclePhase.AFTER_TOOL_RESULT;
      case TURN_COMMITTED, TURN_FAILED -> PluginLifecyclePhase.AFTER_TURN;
      default -> PluginLifecyclePhase.UNSPECIFIED;
    };
  }

  private static PluginTapOutcome outcome(TurnLifecycleEvent event) {
    return switch (event.type()) {
      case MODEL_COMPLETED ->
          event.status().equals("INVALID") ? PluginTapOutcome.FAILED : PluginTapOutcome.COMPLETED;
      case TOOL_CALL_COMPLETED ->
          event.status().equals("SUCCESS") ? PluginTapOutcome.COMPLETED : PluginTapOutcome.FAILED;
      case TURN_COMMITTED -> PluginTapOutcome.COMPLETED;
      case TURN_FAILED -> PluginTapOutcome.FAILED;
      default -> PluginTapOutcome.ACCEPTED;
    };
  }

  private static String hash(TurnLifecycleEvent event, PluginLifecyclePhase phase) {
    try {
      String projection =
          "plugin-lifecycle-v2\u0000"
              + phase.name()
              + '\u0000'
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
                  .digest(projection.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 不可用", unavailable);
    }
  }
}
