package io.namei.agent.kernel.plugin;

import java.util.Objects;
import java.util.regex.Pattern;

public record PluginTapEvent(
    PluginCapability capability,
    PluginLifecyclePhase phase,
    String referenceHash,
    PluginTapOutcome outcome,
    PluginStableCode code,
    long durationMillis) {
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  public PluginTapEvent(
      PluginCapability capability,
      String referenceHash,
      PluginTapOutcome outcome,
      PluginStableCode code,
      long durationMillis) {
    this(
        capability, PluginLifecyclePhase.UNSPECIFIED, referenceHash, outcome, code, durationMillis);
  }

  public PluginTapEvent {
    capability = Objects.requireNonNull(capability, "capability");
    phase = Objects.requireNonNull(phase, "phase");
    if ((capability == PluginCapability.LIFECYCLE_TAP)
        != (phase != PluginLifecyclePhase.UNSPECIFIED)) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
    if (referenceHash == null || !SHA_256.matcher(referenceHash).matches()) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
    outcome = Objects.requireNonNull(outcome, "outcome");
    if (durationMillis < 0 || durationMillis > 86_400_000L) {
      throw PluginContract.violation(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
  }
}
