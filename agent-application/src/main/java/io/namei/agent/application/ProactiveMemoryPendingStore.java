package io.namei.agent.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Unconnected P3 persistence boundary; no implementation is registered during R14-P3. */
interface ProactiveMemoryPendingStore {
  ProactiveMemoryOperation create(
      ProactiveMemoryOperation operation,
      ApprovalInboxEntry approval,
      EncryptedProactiveMemoryCapsule capsule);

  default Optional<ProactiveMemoryOperation> find(ProactiveMemoryOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    return Optional.empty();
  }

  default Optional<EncryptedProactiveMemoryCapsule> loadEncryptedCapsule(
      ProactiveMemoryOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    return Optional.empty();
  }

  default ProactiveMemoryReservation reserveApproved(
      ProactiveMemoryOperationReference reference, Instant observedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    return ProactiveMemoryReservation.notReservable();
  }

  default void markSucceeded(
      ProactiveMemoryOperationReference reference,
      ProactiveMemorySafeReceipt receipt,
      Instant observedAt) {
    throw new UnsupportedOperationException("主动记忆 Store 不支持成功状态");
  }

  default void markUnknown(
      ProactiveMemoryOperationReference reference, String code, Instant observedAt) {
    throw new UnsupportedOperationException("主动记忆 Store 不支持未知状态");
  }

  default boolean commitAnchor(ProactiveMemoryOperationReference reference, Instant observedAt) {
    throw new UnsupportedOperationException("主动记忆 Store 不支持 Anchor 提交");
  }

  default void markCommitUnreported(
      ProactiveMemoryOperationReference reference, Instant observedAt) {
    throw new UnsupportedOperationException("主动记忆 Store 不支持未报告提交状态");
  }
}

record ProactiveMemoryReservation(Status status, Optional<ProactiveMemoryOperation> operation) {
  enum Status {
    RESERVED,
    NOT_RESERVABLE
  }

  ProactiveMemoryReservation {
    status = Objects.requireNonNull(status, "status");
    operation = Objects.requireNonNull(operation, "operation");
    if ((status == Status.RESERVED) != operation.isPresent()) {
      throw new IllegalArgumentException("主动记忆 Reservation 状态无效");
    }
  }

  static ProactiveMemoryReservation reserved(ProactiveMemoryOperation operation) {
    return new ProactiveMemoryReservation(Status.RESERVED, Optional.of(operation));
  }

  static ProactiveMemoryReservation notReservable() {
    return new ProactiveMemoryReservation(Status.NOT_RESERVABLE, Optional.empty());
  }

  boolean acquired() {
    return status == Status.RESERVED;
  }
}

record ProactiveMemorySafeReceipt(String code) {
  ProactiveMemorySafeReceipt {
    if (code == null || !(code.equals("CREATED") || code.equals("REINFORCED"))) {
      throw new IllegalArgumentException("主动记忆安全 Receipt code 无效");
    }
  }
}
