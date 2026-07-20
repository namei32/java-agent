package io.namei.agent.application;

import io.namei.agent.kernel.proactive.LocalFakePeerResult;
import io.namei.agent.kernel.proactive.PeerTaskRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Unconnected P4 persistence boundary. No implementation is registered in Bootstrap. */
interface LocalFakePeerPendingStore {
  LocalFakePeerTaskOperation create(
      LocalFakePeerTaskOperation operation,
      ApprovalInboxEntry approval,
      EncryptedLocalFakePeerCapsule capsule);

  default Optional<LocalFakePeerTaskOperation> find(PeerTaskRef reference) {
    Objects.requireNonNull(reference, "reference");
    return Optional.empty();
  }

  default Optional<EncryptedLocalFakePeerCapsule> loadEncryptedCapsule(PeerTaskRef reference) {
    Objects.requireNonNull(reference, "reference");
    return Optional.empty();
  }

  default LocalFakePeerReservation reserveApproved(PeerTaskRef reference, Instant observedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    return LocalFakePeerReservation.notReservable();
  }

  /** Atomically latches a running task as cancelled before the coordinator asks the Fake Port. */
  default boolean cancelRunning(PeerTaskRef reference, Instant observedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    return false;
  }

  default void markTerminal(PeerTaskRef reference, LocalFakePeerResult result, Instant observedAt) {
    throw new UnsupportedOperationException("本地 Fake Peer Store 不支持终态");
  }

  default void markUnknown(PeerTaskRef reference, String code, Instant observedAt) {
    throw new UnsupportedOperationException("本地 Fake Peer Store 不支持未知状态");
  }

  default boolean commitAnchor(PeerTaskRef reference, Instant observedAt) {
    throw new UnsupportedOperationException("本地 Fake Peer Store 不支持 Anchor 提交");
  }

  default void markCommitUnreported(PeerTaskRef reference, Instant observedAt) {
    throw new UnsupportedOperationException("本地 Fake Peer Store 不支持未报告提交状态");
  }
}

record LocalFakePeerReservation(Status status, Optional<LocalFakePeerTaskOperation> operation) {
  enum Status {
    RESERVED,
    NOT_RESERVABLE
  }

  LocalFakePeerReservation {
    status = Objects.requireNonNull(status, "status");
    operation = Objects.requireNonNull(operation, "operation");
    if ((status == Status.RESERVED) != operation.isPresent()) {
      throw new IllegalArgumentException("本地 Fake Peer Reservation 状态无效");
    }
  }

  static LocalFakePeerReservation reserved(LocalFakePeerTaskOperation operation) {
    return new LocalFakePeerReservation(Status.RESERVED, Optional.of(operation));
  }

  static LocalFakePeerReservation notReservable() {
    return new LocalFakePeerReservation(Status.NOT_RESERVABLE, Optional.empty());
  }

  boolean acquired() {
    return status == Status.RESERVED;
  }
}
