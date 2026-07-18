package io.namei.agent.bootstrap.control;

import io.namei.agent.application.control.ActiveTurnRegistrySnapshot;
import io.namei.agent.application.control.ActiveTurnSnapshot;
import io.namei.agent.kernel.control.ControlPlaneContract;
import java.time.Instant;
import java.util.List;

public record ControlTurnsResponse(int schemaVersion, Instant observedAt, List<Item> items) {
  public ControlTurnsResponse {
    items = List.copyOf(items);
  }

  static ControlTurnsResponse from(Instant observedAt, ActiveTurnRegistrySnapshot snapshot) {
    return new ControlTurnsResponse(
        ControlPlaneContract.CURRENT_VERSION,
        observedAt,
        snapshot.activeTurns().stream().map(Item::from).toList());
  }

  public record Item(
      String turnRef,
      String channel,
      String state,
      Instant startedAt,
      Long lastSequence,
      int subscriberCount) {
    static Item from(ActiveTurnSnapshot snapshot) {
      return new Item(
          snapshot.turnRef().value(),
          snapshot.channel(),
          snapshot.state().name(),
          snapshot.startedAt(),
          snapshot.lastSequence(),
          snapshot.subscriberCount());
    }

    @Override
    public String toString() {
      return "Item[turnRef=<redacted>, channel="
          + channel
          + ", state="
          + state
          + ", startedAt="
          + startedAt
          + ", lastSequence="
          + lastSequence
          + ", subscriberCount="
          + subscriberCount
          + "]";
    }
  }
}
