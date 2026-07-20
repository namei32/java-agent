package io.namei.agent.application;

import java.time.Clock;
import java.util.Objects;

/**
 * Explicit P6 recovery only. It is neither a worker nor an automatic memory/optimizer execution
 * path.
 */
final class ProactiveMemoryNoteWriteRecoveryCoordinator {
  private static final String UNKNOWN_WRITE = "PROACTIVE_MEMORY_NOTE_WRITE_UNCERTAIN";
  private static final String UNKNOWN_AUDIT = "PROACTIVE_MEMORY_NOTE_AUDIT_UNCERTAIN";
  private static final String UNKNOWN_LEDGER = "PROACTIVE_MEMORY_NOTE_LEDGER_UNCERTAIN";

  private final ProactiveMemoryNoteWritePendingStore store;
  private final ProactiveMemoryNoteWriteCapsuleCipher cipher;
  private final ProactiveMemoryNoteWriteCapability capability;
  private final ProactiveAudit audit;
  private final Clock clock;

  ProactiveMemoryNoteWriteRecoveryCoordinator(
      ProactiveMemoryNoteWritePendingStore store,
      ProactiveMemoryNoteWriteCapsuleCipher cipher,
      ProactiveMemoryNoteWriteCapability capability,
      ProactiveAudit audit,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.capability = Objects.requireNonNull(capability, "capability");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  Outcome resume(ProactiveMemoryNoteWriteOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    ProactiveMemoryNoteWriteOperation operation = store.find(reference).orElse(null);
    if (!isPending(operation)) {
      return Outcome.NOT_STARTED;
    }
    ProactiveMemoryNoteWriteCapsule capsule = loadVerifiedCapsule(operation, reference);
    if (capsule == null) {
      return Outcome.NOT_STARTED;
    }

    var reservation = store.reserveApproved(reference, clock.instant());
    if (!reservation.acquired()) {
      return Outcome.NOT_STARTED;
    }
    ProactiveMemoryNoteWriteOperation reserved = reservation.operation().orElseThrow();
    if (reserved.state() != ProactiveMemoryNoteWriteOperation.State.CONSUMING
        || reserved.anchor().state() != ProactiveMemoryNoteWriteAnchor.State.PENDING_APPROVAL
        || !capsule.matches(reserved)) {
      markUnknown(reference, UNKNOWN_AUDIT);
      return Outcome.UNKNOWN;
    }
    try {
      audit.record(
          new ProactiveAuditEvent(
              reserved.anchor().targetHash(),
              ProactiveAuditEvent.Action.MEMORY,
              java.util.Optional.empty(),
              clock.instant()));
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_AUDIT);
      return Outcome.UNKNOWN;
    }

    ProactiveMemoryNoteWriteSafeReceipt receipt;
    try {
      receipt = capability.write(reserved, capsule);
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_WRITE);
      return Outcome.UNKNOWN;
    }
    try {
      store.markSucceeded(reference, receipt, clock.instant());
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_LEDGER);
      return Outcome.UNKNOWN;
    }
    try {
      if (store.commitAnchor(reference, clock.instant())) {
        return Outcome.COMMITTED;
      }
    } catch (RuntimeException ignored) {
      // A safe receipt has already been recorded. P6 must never automatically write it again.
    }
    store.markCommitUnreported(reference, clock.instant());
    return Outcome.COMMIT_UNREPORTED;
  }

  private ProactiveMemoryNoteWriteCapsule loadVerifiedCapsule(
      ProactiveMemoryNoteWriteOperation operation,
      ProactiveMemoryNoteWriteOperationReference reference) {
    try {
      var encrypted = store.loadEncryptedCapsule(reference).orElse(null);
      if (encrypted == null) {
        return null;
      }
      ProactiveMemoryNoteWriteCapsule capsule = cipher.decrypt(operation, encrypted);
      return capsule.matches(operation) ? capsule : null;
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private static boolean isPending(ProactiveMemoryNoteWriteOperation operation) {
    return operation != null
        && !operation.isTerminal()
        && operation.anchor().state() == ProactiveMemoryNoteWriteAnchor.State.PENDING_APPROVAL;
  }

  private void markUnknown(ProactiveMemoryNoteWriteOperationReference reference, String code) {
    store.markUnknown(reference, code, clock.instant());
  }

  enum Outcome {
    COMMITTED,
    UNKNOWN,
    COMMIT_UNREPORTED,
    NOT_STARTED
  }
}
