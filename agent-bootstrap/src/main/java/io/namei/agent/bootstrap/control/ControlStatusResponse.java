package io.namei.agent.bootstrap.control;

import java.time.Instant;
import java.util.List;

public record ControlStatusResponse(
    int schemaVersion, Instant observedAt, Host host, Control control, List<Channel> channels) {
  public ControlStatusResponse {
    channels = List.copyOf(channels);
  }

  public record Host(String state, String code) {}

  public record Control(
      String state,
      String code,
      int activeTurns,
      int recentTerminalTombstones,
      int eventSubscribers,
      int maxSubscriberQueueDepth,
      int subscriberBufferCapacity,
      long slowConsumerDisconnects) {}

  public record Channel(
      String name,
      String state,
      String code,
      int activeTurns,
      int consecutiveFailures,
      Reliability reliability) {}

  public record Reliability(
      String mode,
      String ledgerState,
      long pendingDeliveries,
      long unknownExecutions,
      long unknownDeliveries,
      String code) {}
}
