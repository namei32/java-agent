package io.namei.agent.application;

import java.time.Clock;
import java.util.Objects;

/**
 * Explicit P2-C recovery for the injected Fake Delivery port. It is not a Scheduler worker or a
 * channel adapter.
 */
final class ProactiveDeliveryRecoveryCoordinator {
  private static final String UNKNOWN_INVOKER = "PROACTIVE_DELIVERY_INVOKER_UNCERTAIN";
  private static final String UNKNOWN_AUDIT = "PROACTIVE_DELIVERY_AUDIT_UNCERTAIN";
  private static final String UNKNOWN_LEDGER = "PROACTIVE_DELIVERY_LEDGER_UNCERTAIN";

  private final ProactiveDeliveryPendingStore store;
  private final ProactiveDeliveryCapsuleCipher cipher;
  private final ProactiveDeliveryPort port;
  private final ProactiveAudit audit;
  private final Clock clock;

  ProactiveDeliveryRecoveryCoordinator(
      ProactiveDeliveryPendingStore store,
      ProactiveDeliveryCapsuleCipher cipher,
      ProactiveDeliveryPort port,
      ProactiveAudit audit,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.port = Objects.requireNonNull(port, "port");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  Outcome resume(ProactiveDeliveryOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    ProactiveDeliveryOperation operation = store.find(reference).orElse(null);
    if (!isPending(operation)) {
      return Outcome.NOT_STARTED;
    }
    ProactiveDeliveryCapsule capsule = loadVerifiedCapsule(operation, reference);
    if (capsule == null) {
      return Outcome.NOT_STARTED;
    }

    var reservation = store.reserveApproved(reference, clock.instant());
    if (!reservation.acquired()) {
      return Outcome.NOT_STARTED;
    }
    ProactiveDeliveryOperation reserved = reservation.operation().orElseThrow();
    if (reserved.state() != ProactiveDeliveryOperation.State.CONSUMING
        || reserved.anchor().state() != ProactiveDeliveryAnchor.State.PENDING_APPROVAL
        || !capsule.matches(reserved)) {
      markUnknown(reference, UNKNOWN_AUDIT);
      return Outcome.UNKNOWN;
    }
    try {
      audit.record(
          new ProactiveAuditEvent(
              reserved.anchor().targetHash(),
              ProactiveAuditEvent.Action.DELIVERY,
              java.util.Optional.empty(),
              clock.instant()));
    } catch (RuntimeException failure) {
      markUnknown(reference, UNKNOWN_AUDIT);
      return Outcome.UNKNOWN;
    }

    ProactiveDeliverySafeReceipt receipt;
    try {
      receipt =
          Objects.requireNonNull(
              port.deliver(
                  new ProactiveDeliveryCommand(reference, capsule.recipient(), capsule.source())),
              "Fake Delivery receipt");
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
      // The safe receipt is already durable; do not retry the Fake Port.
    }
    store.markCommitUnreported(reference, clock.instant());
    return Outcome.COMMIT_UNREPORTED;
  }

  private ProactiveDeliveryCapsule loadVerifiedCapsule(
      ProactiveDeliveryOperation operation, ProactiveDeliveryOperationReference reference) {
    try {
      var encrypted = store.loadEncryptedCapsule(reference).orElse(null);
      if (encrypted == null) {
        return null;
      }
      ProactiveDeliveryCapsule capsule = cipher.decrypt(operation, encrypted);
      return capsule.matches(operation) ? capsule : null;
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private static boolean isPending(ProactiveDeliveryOperation operation) {
    return operation != null
        && !operation.isTerminal()
        && operation.anchor().state() == ProactiveDeliveryAnchor.State.PENDING_APPROVAL;
  }

  private void markUnknown(ProactiveDeliveryOperationReference reference, String code) {
    store.markUnknown(reference, code, clock.instant());
  }

  public enum Outcome {
    COMMITTED,
    UNKNOWN,
    COMMIT_UNREPORTED,
    NOT_STARTED
  }
}

@FunctionalInterface
interface ProactiveDeliveryPort {
  ProactiveDeliverySafeReceipt deliver(ProactiveDeliveryCommand command);
}

/** Sensitive command whose only consumer is the injected P2-C Fake Port. */
record ProactiveDeliveryCommand(
    ProactiveDeliveryOperationReference reference,
    FakeProactiveRecipientReference recipient,
    io.namei.agent.kernel.proactive.ProactiveSourceItem source) {
  ProactiveDeliveryCommand {
    reference = Objects.requireNonNull(reference, "reference");
    recipient = Objects.requireNonNull(recipient, "recipient");
    source = Objects.requireNonNull(source, "source");
  }

  @Override
  public String toString() {
    return "ProactiveDeliveryCommand[reference=<redacted>, recipient=<redacted>, source=<redacted>]";
  }
}
