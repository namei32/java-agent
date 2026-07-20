package io.namei.agent.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Unconnected P6 persistence boundary. R14 provides no production implementation or registration.
 */
interface ProactiveMemoryNoteWritePendingStore {
  ProactiveMemoryNoteWriteOperation create(
      ProactiveMemoryNoteWriteOperation operation,
      ApprovalInboxEntry approval,
      EncryptedProactiveMemoryNoteWriteCapsule capsule);

  default Optional<ProactiveMemoryNoteWriteOperation> find(
      ProactiveMemoryNoteWriteOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    return Optional.empty();
  }

  default Optional<EncryptedProactiveMemoryNoteWriteCapsule> loadEncryptedCapsule(
      ProactiveMemoryNoteWriteOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    return Optional.empty();
  }

  default ProactiveMemoryNoteWriteReservation reserveApproved(
      ProactiveMemoryNoteWriteOperationReference reference, Instant observedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    return ProactiveMemoryNoteWriteReservation.notReservable();
  }

  default void markSucceeded(
      ProactiveMemoryNoteWriteOperationReference reference,
      ProactiveMemoryNoteWriteSafeReceipt receipt,
      Instant observedAt) {
    throw new UnsupportedOperationException("主动 NOTE 写入 Store 不支持成功状态");
  }

  default void markUnknown(
      ProactiveMemoryNoteWriteOperationReference reference, String code, Instant observedAt) {
    throw new UnsupportedOperationException("主动 NOTE 写入 Store 不支持未知状态");
  }

  default boolean commitAnchor(
      ProactiveMemoryNoteWriteOperationReference reference, Instant observedAt) {
    throw new UnsupportedOperationException("主动 NOTE 写入 Store 不支持 Anchor 提交");
  }

  default void markCommitUnreported(
      ProactiveMemoryNoteWriteOperationReference reference, Instant observedAt) {
    throw new UnsupportedOperationException("主动 NOTE 写入 Store 不支持未报告提交状态");
  }
}

record ProactiveMemoryNoteWriteReservation(
    Status status, Optional<ProactiveMemoryNoteWriteOperation> operation) {
  enum Status {
    RESERVED,
    NOT_RESERVABLE
  }

  ProactiveMemoryNoteWriteReservation {
    status = Objects.requireNonNull(status, "status");
    operation = Objects.requireNonNull(operation, "operation");
    if ((status == Status.RESERVED) != operation.isPresent()) {
      throw new IllegalArgumentException("主动 NOTE 写入 Reservation 状态无效");
    }
  }

  static ProactiveMemoryNoteWriteReservation reserved(ProactiveMemoryNoteWriteOperation operation) {
    return new ProactiveMemoryNoteWriteReservation(Status.RESERVED, Optional.of(operation));
  }

  static ProactiveMemoryNoteWriteReservation notReservable() {
    return new ProactiveMemoryNoteWriteReservation(Status.NOT_RESERVABLE, Optional.empty());
  }

  boolean acquired() {
    return status == Status.RESERVED;
  }
}
