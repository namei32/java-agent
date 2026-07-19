package io.namei.agent.kernel.control;

/** Stable, source-free failure used when a safe history projection cannot be produced. */
public final class HistorySnapshotUnavailableException extends RuntimeException {
  public HistorySnapshotUnavailableException() {
    super("控制历史快照不可用");
  }
}
