package io.namei.agent.bootstrap.control;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.control-plane")
public final class ControlPlaneProperties {
  private final ControlPlaneMode mode;
  private final Duration sessionTtl;
  private final int maxSessions;
  private final int maxActiveTurns;
  private final Duration terminalRetention;
  private final int maxTerminalTombstones;
  private final int maxSubscribers;
  private final int subscriberBufferCapacity;
  private final Duration heartbeatInterval;
  private final Duration streamMaxLifetime;
  private final Duration shutdownTimeout;

  @ConstructorBinding
  public ControlPlaneProperties(
      @DefaultValue("DISABLED") String mode,
      @DefaultValue("15m") Duration sessionTtl,
      @DefaultValue("4") int maxSessions,
      @DefaultValue("128") int maxActiveTurns,
      @DefaultValue("5m") Duration terminalRetention,
      @DefaultValue("1024") int maxTerminalTombstones,
      @DefaultValue("8") int maxSubscribers,
      @DefaultValue("64") int subscriberBufferCapacity,
      @DefaultValue("15s") Duration heartbeatInterval,
      @DefaultValue("15m") Duration streamMaxLifetime,
      @DefaultValue("2s") Duration shutdownTimeout) {
    this.mode = ControlPlaneMode.parse(mode);
    this.sessionTtl =
        requireDuration(sessionTtl, Duration.ofMinutes(1), Duration.ofHours(1), "session-ttl");
    this.maxSessions = requireRange(maxSessions, 1, 16, "max-sessions");
    this.maxActiveTurns = requireRange(maxActiveTurns, 1, 1_024, "max-active-turns");
    this.terminalRetention =
        requireDuration(
            terminalRetention, Duration.ofMinutes(1), Duration.ofMinutes(30), "terminal-retention");
    this.maxTerminalTombstones =
        requireRange(maxTerminalTombstones, 16, 4_096, "max-terminal-tombstones");
    this.maxSubscribers = requireRange(maxSubscribers, 1, 32, "max-subscribers");
    this.subscriberBufferCapacity =
        requireRange(subscriberBufferCapacity, 1, 256, "subscriber-buffer-capacity");
    this.heartbeatInterval =
        requireDuration(
            heartbeatInterval, Duration.ofSeconds(5), Duration.ofSeconds(60), "heartbeat-interval");
    this.streamMaxLifetime =
        requireDuration(
            streamMaxLifetime, Duration.ofMinutes(1), Duration.ofHours(1), "stream-max-lifetime");
    this.shutdownTimeout =
        requireDuration(
            shutdownTimeout, Duration.ofMillis(100), Duration.ofSeconds(10), "shutdown-timeout");
  }

  public ControlPlaneMode mode() {
    return mode;
  }

  public Duration sessionTtl() {
    return sessionTtl;
  }

  public int maxSessions() {
    return maxSessions;
  }

  public int maxActiveTurns() {
    return maxActiveTurns;
  }

  public Duration terminalRetention() {
    return terminalRetention;
  }

  public int maxTerminalTombstones() {
    return maxTerminalTombstones;
  }

  public int maxSubscribers() {
    return maxSubscribers;
  }

  public int subscriberBufferCapacity() {
    return subscriberBufferCapacity;
  }

  public Duration heartbeatInterval() {
    return heartbeatInterval;
  }

  public Duration streamMaxLifetime() {
    return streamMaxLifetime;
  }

  public Duration shutdownTimeout() {
    return shutdownTimeout;
  }

  @Override
  public String toString() {
    return "ControlPlaneProperties[mode=" + mode + ", budgets=<configured>]";
  }

  private static int requireRange(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          "agent.control-plane." + field + " 必须在 " + minimum + ".." + maximum + " 之间");
    }
    return value;
  }

  private static Duration requireDuration(
      Duration value, Duration minimum, Duration maximum, String field) {
    if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("agent.control-plane." + field + " 超出批准边界");
    }
    return value;
  }
}
