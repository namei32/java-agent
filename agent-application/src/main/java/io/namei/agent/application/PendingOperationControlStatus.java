package io.namei.agent.application;

import java.time.Instant;
import java.util.Objects;

/** Public-safe status projection with no approval, capsule, ledger, or conversation fields. */
public record PendingOperationControlStatus(int schemaVersion, String state, Instant updatedAt) {
  public static final int SCHEMA_VERSION = 1;

  public PendingOperationControlStatus {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("不支持的 Pending Operation Control 状态版本");
    }
    state = Objects.requireNonNull(state, "state").strip();
    if (state.isBlank()) {
      throw new IllegalArgumentException("Pending Operation 状态不能为空");
    }
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  static PendingOperationControlStatus from(PendingOperation operation) {
    Objects.requireNonNull(operation, "operation");
    return new PendingOperationControlStatus(
        SCHEMA_VERSION, operation.state().name(), operation.stateChangedAt());
  }
}
