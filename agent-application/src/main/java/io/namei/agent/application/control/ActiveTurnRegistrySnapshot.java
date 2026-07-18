package io.namei.agent.application.control;

import java.util.List;

public record ActiveTurnRegistrySnapshot(
    List<ActiveTurnSnapshot> activeTurns, int terminalTombstones, boolean saturated) {
  public ActiveTurnRegistrySnapshot {
    activeTurns = List.copyOf(activeTurns);
    if (terminalTombstones < 0) {
      throw new IllegalArgumentException("控制面 Tombstone 数不能为负数");
    }
  }

  @Override
  public String toString() {
    return "ActiveTurnRegistrySnapshot[activeTurns="
        + activeTurns
        + ", terminalTombstones="
        + terminalTombstones
        + ", saturated="
        + saturated
        + "]";
  }
}
