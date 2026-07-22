package io.namei.agent.application;

/** 已批准 Operation 向其 Ledger Slot 进行一次性非执行移交的结果。 */
public enum PendingOperationReservationStatus {
  RESERVED,
  ALREADY_RESERVED,
  PENDING_APPROVAL,
  DENIED,
  EXPIRED,
  CANCELLED,
  STALE_SESSION,
  NOT_RESERVABLE,
  NOT_FOUND
}
