package io.namei.agent.application;

import io.namei.agent.kernel.proactive.LocalFakePeerCard;
import io.namei.agent.kernel.proactive.LocalFakePeerResult;
import io.namei.agent.kernel.proactive.PeerTaskRef;
import java.time.Clock;
import java.util.Objects;

/**
 * Explicit P4 recovery for an injected Fake peer-process port. It is not a process launcher,
 * worker, A2A client, or network adapter.
 */
final class LocalFakePeerProcessRecoveryCoordinator {
  private static final String UNKNOWN_INVOKER = "LOCAL_FAKE_PEER_INVOKER_UNCERTAIN";
  private static final String UNKNOWN_AUDIT = "LOCAL_FAKE_PEER_AUDIT_UNCERTAIN";
  private static final String UNKNOWN_LEDGER = "LOCAL_FAKE_PEER_LEDGER_UNCERTAIN";

  private final LocalFakePeerPendingStore store;
  private final LocalFakePeerCapsuleCipher cipher;
  private final FakePeerProcessPort port;
  private final ProactiveAudit audit;
  private final Clock clock;

  LocalFakePeerProcessRecoveryCoordinator(
      LocalFakePeerPendingStore store,
      LocalFakePeerCapsuleCipher cipher,
      FakePeerProcessPort port,
      ProactiveAudit audit,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.port = Objects.requireNonNull(port, "port");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  Outcome resume(PeerTaskRef reference) {
    Objects.requireNonNull(reference, "reference");
    LocalFakePeerTaskOperation operation = store.find(reference).orElse(null);
    if (!isPending(operation)) {
      return Outcome.NOT_STARTED;
    }
    LocalFakePeerCapsule capsule = loadVerifiedCapsule(operation, reference);
    if (capsule == null) {
      return Outcome.NOT_STARTED;
    }

    LocalFakePeerReservation reservation = store.reserveApproved(reference, clock.instant());
    if (!reservation.acquired()) {
      return Outcome.NOT_STARTED;
    }
    LocalFakePeerTaskOperation reserved = reservation.operation().orElseThrow();
    if (reserved.state() != LocalFakePeerTaskOperation.State.RUNNING
        || reserved.anchor().state() != LocalFakePeerAnchor.State.PENDING_APPROVAL
        || !capsule.matches(reserved)) {
      markUnknown(reference, UNKNOWN_AUDIT);
      return Outcome.UNKNOWN;
    }
    try {
      audit.record(
          new ProactiveAuditEvent(
              reserved.anchor().auditTargetHash(),
              ProactiveAuditEvent.Action.PEER,
              java.util.Optional.empty(),
              clock.instant()));
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_AUDIT);
      return Outcome.UNKNOWN;
    }

    LocalFakePeerResult result;
    try {
      result =
          Objects.requireNonNull(
              port.execute(new LocalFakePeerProcessCommand(reference, capsule.card())),
              "Fake Peer result");
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_INVOKER);
      return Outcome.UNKNOWN;
    }
    if (isCancelled(reference)) {
      return Outcome.CANCELLED;
    }
    try {
      store.markTerminal(reference, result, clock.instant());
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_LEDGER);
      return Outcome.UNKNOWN;
    }
    try {
      if (store.commitAnchor(reference, clock.instant())) {
        return Outcome.COMMITTED;
      }
    } catch (RuntimeException ignored) {
      // The terminal Fake result is durable; never replay this port.
    }
    store.markCommitUnreported(reference, clock.instant());
    return Outcome.COMMIT_UNREPORTED;
  }

  /**
   * Requests cancellation only for an already running P4 Fake task. It never touches a real process
   * tree and cannot start or replay a task.
   */
  Outcome cancel(PeerTaskRef reference) {
    Objects.requireNonNull(reference, "reference");
    if (!store.cancelRunning(reference, clock.instant())) {
      return Outcome.NOT_STARTED;
    }
    try {
      port.cancel(reference);
      return Outcome.CANCELLED;
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_INVOKER);
      return Outcome.UNKNOWN;
    }
  }

  private LocalFakePeerCapsule loadVerifiedCapsule(
      LocalFakePeerTaskOperation operation, PeerTaskRef reference) {
    try {
      EncryptedLocalFakePeerCapsule encrypted = store.loadEncryptedCapsule(reference).orElse(null);
      if (encrypted == null) {
        return null;
      }
      LocalFakePeerCapsule capsule = cipher.decrypt(operation, encrypted);
      return capsule.matches(operation) ? capsule : null;
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private static boolean isPending(LocalFakePeerTaskOperation operation) {
    return operation != null
        && !operation.isTerminal()
        && operation.anchor().state() == LocalFakePeerAnchor.State.PENDING_APPROVAL;
  }

  private boolean isCancelled(PeerTaskRef reference) {
    return store
        .find(reference)
        .map(operation -> operation.state() == LocalFakePeerTaskOperation.State.CANCELLED)
        .orElse(false);
  }

  private void markUnknown(PeerTaskRef reference, String code) {
    store.markUnknown(reference, code, clock.instant());
  }

  enum Outcome {
    COMMITTED,
    UNKNOWN,
    COMMIT_UNREPORTED,
    CANCELLED,
    NOT_STARTED
  }
}

@FunctionalInterface
interface FakePeerProcessPort {
  LocalFakePeerResult execute(LocalFakePeerProcessCommand command);

  default void cancel(PeerTaskRef reference) {
    Objects.requireNonNull(reference, "reference");
  }
}

/**
 * Sensitive command limited to the injected P4 Fake Port; it carries no task body or process data.
 */
record LocalFakePeerProcessCommand(PeerTaskRef reference, LocalFakePeerCard card) {
  LocalFakePeerProcessCommand {
    reference = Objects.requireNonNull(reference, "reference");
    card = Objects.requireNonNull(card, "card");
    if (!card.equals(LocalFakePeerCard.approved())) {
      throw new IllegalArgumentException("仅允许批准的本地 Fake Peer Card");
    }
  }

  @Override
  public String toString() {
    return "LocalFakePeerProcessCommand[reference=<redacted>, card=<redacted>]";
  }
}
