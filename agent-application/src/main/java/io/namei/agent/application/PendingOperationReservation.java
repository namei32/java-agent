package io.namei.agent.application;

import java.util.Objects;
import java.util.Optional;

/**
 * A repository-authoritative Reservation result. Only {@link #acquired()} may ever grant a future
 * executor the right to begin an invocation; this type itself never invokes a Tool.
 */
public record PendingOperationReservation(
    PendingOperationReservationStatus status, Optional<PendingOperation> operation) {
  public PendingOperationReservation {
    status = Objects.requireNonNull(status, "status");
    operation = Objects.requireNonNull(operation, "operation");
    if ((status == PendingOperationReservationStatus.NOT_FOUND) != operation.isEmpty()) {
      throw new IllegalArgumentException("待审批操作 Reservation 状态与记录不一致");
    }
    if ((status == PendingOperationReservationStatus.RESERVED
            || status == PendingOperationReservationStatus.ALREADY_RESERVED)
        && operation
            .map(PendingOperation::state)
            .filter(state -> state == PendingOperationState.CONSUMING)
            .isEmpty()) {
      throw new IllegalArgumentException("Reservation 必须绑定 CONSUMING 操作");
    }
  }

  public static PendingOperationReservation notFound() {
    return new PendingOperationReservation(
        PendingOperationReservationStatus.NOT_FOUND, Optional.empty());
  }

  public static PendingOperationReservation of(
      PendingOperationReservationStatus status, PendingOperation operation) {
    return new PendingOperationReservation(
        status, Optional.of(Objects.requireNonNull(operation, "operation")));
  }

  /**
   * True exactly once, after the Approval consumption and durable Reservation committed together.
   */
  public boolean acquired() {
    return status == PendingOperationReservationStatus.RESERVED;
  }

  @Override
  public String toString() {
    return "PendingOperationReservation[status=" + status + ", operation=<redacted>]";
  }
}
