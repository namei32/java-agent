package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.proactive.ProactiveSourceItem;
import java.time.Clock;
import java.util.Objects;

/**
 * Explicit P3 recovery for the injected Fake memory-mutation port. It is not a worker, scheduler,
 * memory repository, or automatically executing optimizer.
 */
final class ProactiveMemoryRecoveryCoordinator {
  private static final String UNKNOWN_INVOKER = "PROACTIVE_MEMORY_INVOKER_UNCERTAIN";
  private static final String UNKNOWN_AUDIT = "PROACTIVE_MEMORY_AUDIT_UNCERTAIN";
  private static final String UNKNOWN_LEDGER = "PROACTIVE_MEMORY_LEDGER_UNCERTAIN";

  private final ProactiveMemoryPendingStore store;
  private final ProactiveMemoryCapsuleCipher cipher;
  private final ProactiveMemoryMutationPort port;
  private final ProactiveAudit audit;
  private final Clock clock;

  ProactiveMemoryRecoveryCoordinator(
      ProactiveMemoryPendingStore store,
      ProactiveMemoryCapsuleCipher cipher,
      ProactiveMemoryMutationPort port,
      ProactiveAudit audit,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.port = Objects.requireNonNull(port, "port");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  Outcome resume(ProactiveMemoryOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    ProactiveMemoryOperation operation = store.find(reference).orElse(null);
    if (!isPending(operation)) {
      return Outcome.NOT_STARTED;
    }
    ProactiveMemoryCapsule capsule = loadVerifiedCapsule(operation, reference);
    if (capsule == null) {
      return Outcome.NOT_STARTED;
    }

    var reservation = store.reserveApproved(reference, clock.instant());
    if (!reservation.acquired()) {
      return Outcome.NOT_STARTED;
    }
    ProactiveMemoryOperation reserved = reservation.operation().orElseThrow();
    if (reserved.state() != ProactiveMemoryOperation.State.CONSUMING
        || reserved.anchor().state() != ProactiveMemoryAnchor.State.PENDING_APPROVAL
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

    ProactiveMemorySafeReceipt receipt;
    try {
      receipt =
          Objects.requireNonNull(
              port.capture(
                  new ProactiveMemoryMutationCommand(
                      reference,
                      MemoryType.NOTE,
                      capsule.source(),
                      capsule.scopeHash(),
                      ProactiveMemoryCapsule.FIXED_EMBEDDING_PROFILE)),
              "Fake Memory receipt");
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_INVOKER);
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
      // The safe receipt is already durable; never replay the Fake Memory port.
    }
    store.markCommitUnreported(reference, clock.instant());
    return Outcome.COMMIT_UNREPORTED;
  }

  private ProactiveMemoryCapsule loadVerifiedCapsule(
      ProactiveMemoryOperation operation, ProactiveMemoryOperationReference reference) {
    try {
      var encrypted = store.loadEncryptedCapsule(reference).orElse(null);
      if (encrypted == null) {
        return null;
      }
      ProactiveMemoryCapsule capsule = cipher.decrypt(operation, encrypted);
      return capsule.matches(operation) ? capsule : null;
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private static boolean isPending(ProactiveMemoryOperation operation) {
    return operation != null
        && !operation.isTerminal()
        && operation.anchor().state() == ProactiveMemoryAnchor.State.PENDING_APPROVAL;
  }

  private void markUnknown(ProactiveMemoryOperationReference reference, String code) {
    store.markUnknown(reference, code, clock.instant());
  }

  enum Outcome {
    COMMITTED,
    UNKNOWN,
    COMMIT_UNREPORTED,
    NOT_STARTED
  }
}

@FunctionalInterface
interface ProactiveMemoryMutationPort {
  ProactiveMemorySafeReceipt capture(ProactiveMemoryMutationCommand command);
}

/** Sensitive command whose only consumer is the injected P3 Fake Memory port. */
record ProactiveMemoryMutationCommand(
    ProactiveMemoryOperationReference reference,
    MemoryType type,
    ProactiveSourceItem source,
    String scopeHash,
    String embeddingProfile) {
  ProactiveMemoryMutationCommand {
    reference = Objects.requireNonNull(reference, "reference");
    type = Objects.requireNonNull(type, "type");
    source = Objects.requireNonNull(source, "source");
    scopeHash = Objects.requireNonNull(scopeHash, "scopeHash");
    embeddingProfile = Objects.requireNonNull(embeddingProfile, "embeddingProfile");
    if (type != MemoryType.NOTE
        || !scopeHash.matches("[0-9a-f]{64}")
        || !embeddingProfile.equals(ProactiveMemoryCapsule.FIXED_EMBEDDING_PROFILE)) {
      throw new IllegalArgumentException("P3 仅允许 NOTE 类型主动记忆");
    }
  }

  @Override
  public String toString() {
    return "ProactiveMemoryMutationCommand[reference=<redacted>, type="
        + type
        + ", source=<redacted>, scopeHash=<redacted>, embeddingProfile="
        + embeddingProfile
        + "]";
  }
}
