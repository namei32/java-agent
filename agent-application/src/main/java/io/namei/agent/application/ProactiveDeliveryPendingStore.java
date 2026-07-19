package io.namei.agent.application;

import java.util.Objects;
import java.util.Optional;

/**
 * P2's unconnected persistence boundary. Implementations must atomically retain approval, anchor,
 * and encrypted capsule; none is registered in Bootstrap during R14-P2.
 */
interface ProactiveDeliveryPendingStore {
  ProactiveDeliveryOperation create(
      ProactiveDeliveryOperation operation,
      ApprovalInboxEntry approval,
      EncryptedProactiveDeliveryCapsule capsule);

  default Optional<ProactiveDeliveryOperation> find(ProactiveDeliveryOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    return Optional.empty();
  }

  default Optional<EncryptedProactiveDeliveryCapsule> loadEncryptedCapsule(
      ProactiveDeliveryOperationReference reference) {
    Objects.requireNonNull(reference, "reference");
    return Optional.empty();
  }

  default ProactiveDeliveryReservation reserveApproved(
      ProactiveDeliveryOperationReference reference, java.time.Instant observedAt) {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(observedAt, "observedAt");
    return ProactiveDeliveryReservation.notReservable();
  }

  default void markSucceeded(
      ProactiveDeliveryOperationReference reference,
      ProactiveDeliverySafeReceipt receipt,
      java.time.Instant observedAt) {
    throw new UnsupportedOperationException("主动投递 Store 不支持成功状态");
  }

  default void markUnknown(
      ProactiveDeliveryOperationReference reference, String code, java.time.Instant observedAt) {
    throw new UnsupportedOperationException("主动投递 Store 不支持未知状态");
  }

  default boolean commitAnchor(
      ProactiveDeliveryOperationReference reference, java.time.Instant observedAt) {
    throw new UnsupportedOperationException("主动投递 Store 不支持 Anchor 提交");
  }

  default void markCommitUnreported(
      ProactiveDeliveryOperationReference reference, java.time.Instant observedAt) {
    throw new UnsupportedOperationException("主动投递 Store 不支持未报告提交状态");
  }
}

record ProactiveDeliveryReservation(Status status, Optional<ProactiveDeliveryOperation> operation) {
  enum Status {
    RESERVED,
    NOT_RESERVABLE
  }

  ProactiveDeliveryReservation {
    status = Objects.requireNonNull(status, "status");
    operation = Objects.requireNonNull(operation, "operation");
    if ((status == Status.RESERVED) != operation.isPresent()) {
      throw new IllegalArgumentException("主动投递 Reservation 状态无效");
    }
  }

  static ProactiveDeliveryReservation reserved(ProactiveDeliveryOperation operation) {
    return new ProactiveDeliveryReservation(Status.RESERVED, Optional.of(operation));
  }

  static ProactiveDeliveryReservation notReservable() {
    return new ProactiveDeliveryReservation(Status.NOT_RESERVABLE, Optional.empty());
  }

  boolean acquired() {
    return status == Status.RESERVED;
  }
}

record ProactiveDeliverySafeReceipt(String code) {
  ProactiveDeliverySafeReceipt {
    if (code == null || !code.matches("[A-Z0-9_]{1,64}")) {
      throw new IllegalArgumentException("主动投递安全 Receipt code 无效");
    }
  }

  @Override
  public String toString() {
    return "ProactiveDeliverySafeReceipt[code=" + code + "]";
  }
}
