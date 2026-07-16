package io.namei.agent.kernel.channel.reliability;

import java.time.Instant;
import java.util.Objects;

public final class ChannelLedgerCommand {
  private ChannelLedgerCommand() {}

  public record TurnReservation(
      String externalMessageId, String requestFingerprint, String turnId) {
    public TurnReservation {
      externalMessageId =
          ChannelReliabilityValues.identifier(externalMessageId, "External Message ID", 128);
      requestFingerprint = ChannelReliabilityValues.hash(requestFingerprint, "Request Fingerprint");
      turnId = ChannelReliabilityValues.identifier(turnId, "Turn ID", 128);
    }

    @Override
    public String toString() {
      return "TurnReservation[sensitiveFields=<redacted>]";
    }
  }

  public record RecordEvent(
      ChannelInstanceId instance,
      String externalEventId,
      long externalSequence,
      String eventFingerprint,
      InboxEventKind kind,
      String decisionCode,
      String requestFingerprint,
      TurnReservation reservation,
      DeliveryEnvelope feedback,
      Instant recordedAt) {
    public RecordEvent {
      Objects.requireNonNull(instance, "instance");
      externalEventId =
          ChannelReliabilityValues.identifier(externalEventId, "External Event ID", 128);
      ChannelReliabilityValues.externalSequence(externalSequence);
      eventFingerprint = ChannelReliabilityValues.hash(eventFingerprint, "Event Fingerprint");
      Objects.requireNonNull(kind, "kind");
      decisionCode =
          ChannelReliabilityValues.decisionCode(decisionCode, kind != InboxEventKind.TURN);
      requestFingerprint = requestFingerprint == null ? "" : requestFingerprint;
      recordedAt = ChannelReliabilityValues.instant(recordedAt, "recordedAt");
      switch (kind) {
        case TURN -> {
          Objects.requireNonNull(reservation, "reservation");
          if (feedback != null || !decisionCode.isEmpty()) {
            throw new IllegalArgumentException("Turn Event 字段组合无效");
          }
          if (!reservation.requestFingerprint().equals(requestFingerprint)) {
            throw new IllegalArgumentException("Turn Event Request Fingerprint 不一致");
          }
        }
        case FEEDBACK -> {
          if (reservation != null) {
            throw new IllegalArgumentException("Feedback Event 不允许 Turn Reservation");
          }
          if (!requestFingerprint.isEmpty()) {
            requestFingerprint =
                ChannelReliabilityValues.hash(requestFingerprint, "Request Fingerprint");
          }
          requireFeedback(instance, externalEventId, feedback);
        }
        case IGNORED, CONTROL -> {
          if (reservation != null || feedback != null || !requestFingerprint.isEmpty()) {
            throw new IllegalArgumentException(
                "非业务 Event 不允许 Request Fingerprint/Reservation/Feedback");
          }
        }
      }
      String expected =
          ChannelFingerprint.event(
              instance, externalEventId, externalSequence, kind, decisionCode, requestFingerprint);
      if (!expected.equals(eventFingerprint)) {
        throw new IllegalArgumentException("Event Fingerprint 不匹配");
      }
    }

    private static void requireFeedback(
        ChannelInstanceId instance, String externalEventId, DeliveryEnvelope feedback) {
      Objects.requireNonNull(feedback, "feedback");
      if (!instance.equals(feedback.instance())
          || feedback.sourceKind() != DeliverySourceKind.CHANNEL_FEEDBACK
          || !externalEventId.equals(feedback.correlationId())) {
        throw new IllegalArgumentException("Feedback Delivery 与 Event 不一致");
      }
    }

    @Override
    public String toString() {
      return "RecordEvent[kind=" + kind + ", sensitiveFields=<redacted>]";
    }
  }

  public record StartTurn(
      ChannelInstanceId instance,
      String externalEventId,
      String externalMessageId,
      String turnId,
      String ownerId,
      long expectedRevision,
      Instant leaseExpiresAt,
      Instant startedAt) {
    public StartTurn {
      Objects.requireNonNull(instance, "instance");
      externalEventId =
          ChannelReliabilityValues.identifier(externalEventId, "External Event ID", 128);
      externalMessageId =
          ChannelReliabilityValues.identifier(externalMessageId, "External Message ID", 128);
      turnId = ChannelReliabilityValues.identifier(turnId, "Turn ID", 128);
      ownerId = ChannelReliabilityValues.ownerId(ownerId);
      ChannelReliabilityValues.nonNegative(expectedRevision, "expectedRevision");
      leaseExpiresAt = ChannelReliabilityValues.instant(leaseExpiresAt, "leaseExpiresAt");
      startedAt = ChannelReliabilityValues.instant(startedAt, "startedAt");
      if (!leaseExpiresAt.isAfter(startedAt)) {
        throw new IllegalArgumentException("Turn Lease 必须晚于开始时间");
      }
    }

    @Override
    public String toString() {
      return "StartTurn[sensitiveFields=<redacted>]";
    }
  }

  public record RecordTerminal(
      ChannelInstanceId instance,
      String turnId,
      long expectedRevision,
      DeliveryEnvelope delivery,
      Instant recordedAt) {
    public RecordTerminal {
      Objects.requireNonNull(instance, "instance");
      turnId = ChannelReliabilityValues.identifier(turnId, "Turn ID", 128);
      ChannelReliabilityValues.nonNegative(expectedRevision, "expectedRevision");
      Objects.requireNonNull(delivery, "delivery");
      recordedAt = ChannelReliabilityValues.instant(recordedAt, "recordedAt");
      if (!instance.equals(delivery.instance())
          || delivery.sourceKind() != DeliverySourceKind.TURN_TERMINAL
          || !turnId.equals(delivery.correlationId())) {
        throw new IllegalArgumentException("Terminal Delivery 与 Turn 不一致");
      }
    }

    @Override
    public String toString() {
      return "RecordTerminal[sensitiveFields=<redacted>]";
    }
  }

  public record ClaimDelivery(
      ChannelInstanceId instance, String ownerId, Instant leaseExpiresAt, Instant claimedAt) {
    public ClaimDelivery {
      Objects.requireNonNull(instance, "instance");
      ownerId = ChannelReliabilityValues.ownerId(ownerId);
      leaseExpiresAt = ChannelReliabilityValues.instant(leaseExpiresAt, "leaseExpiresAt");
      claimedAt = ChannelReliabilityValues.instant(claimedAt, "claimedAt");
      if (!leaseExpiresAt.isAfter(claimedAt)) {
        throw new IllegalArgumentException("Delivery Lease 必须晚于领取时间");
      }
    }
  }

  public record RecordDeliveryOutcome(
      String deliveryId,
      int partIndex,
      int attemptNumber,
      String ownerId,
      DeliveryAttemptOutcome outcome,
      String remoteMessageId,
      Instant retryAt,
      String errorCode,
      Instant completedAt) {
    public RecordDeliveryOutcome {
      deliveryId = ChannelReliabilityValues.identifier(deliveryId, "Delivery ID", 128);
      if (partIndex < 0 || partIndex >= ChannelReliabilityValues.MAX_PARTS) {
        throw new IllegalArgumentException("Delivery Part Index 超出范围");
      }
      if (attemptNumber < 1 || attemptNumber > ChannelReliabilityValues.MAX_DELIVERY_ATTEMPTS) {
        throw new IllegalArgumentException("Delivery Attempt 超出范围");
      }
      ownerId = ChannelReliabilityValues.ownerId(ownerId);
      Objects.requireNonNull(outcome, "outcome");
      if (outcome == DeliveryAttemptOutcome.STARTED) {
        throw new IllegalArgumentException("Outcome Command 不能记录 STARTED");
      }
      remoteMessageId =
          ChannelReliabilityValues.optionalIdentifier(remoteMessageId, "Remote Message ID", 128);
      errorCode =
          ChannelReliabilityValues.decisionCode(
              errorCode, outcome != DeliveryAttemptOutcome.SUCCEEDED);
      completedAt = ChannelReliabilityValues.instant(completedAt, "completedAt");
      switch (outcome) {
        case SUCCEEDED -> {
          if (remoteMessageId == null || retryAt != null || !errorCode.isEmpty()) {
            throw new IllegalArgumentException("成功投递结果字段无效");
          }
        }
        case RETRYABLE_REJECTED -> {
          if (remoteMessageId != null || retryAt == null || !retryAt.isAfter(completedAt)) {
            throw new IllegalArgumentException("可重试投递结果字段无效");
          }
        }
        case PERMANENT_REJECTED, UNKNOWN -> {
          if (remoteMessageId != null || retryAt != null) {
            throw new IllegalArgumentException("终止投递结果字段无效");
          }
        }
        case STARTED -> throw new IllegalStateException("已拒绝 STARTED");
      }
    }

    @Override
    public String toString() {
      return "RecordDeliveryOutcome[outcome=" + outcome + ", sensitiveFields=<redacted>]";
    }
  }

  public record Recover(
      ChannelInstanceId instance, String newOwnerId, Instant recoveredAt, int batchSize) {
    public Recover {
      Objects.requireNonNull(instance, "instance");
      newOwnerId = ChannelReliabilityValues.ownerId(newOwnerId);
      recoveredAt = ChannelReliabilityValues.instant(recoveredAt, "recoveredAt");
      requireBatch(batchSize);
    }
  }

  public record Cleanup(
      ChannelInstanceId instance,
      Instant cutoff,
      Instant cleanedAt,
      int batchSize,
      int maxInboxRecords,
      int maxDeliveryRecords) {
    public Cleanup {
      Objects.requireNonNull(instance, "instance");
      cutoff = ChannelReliabilityValues.instant(cutoff, "cutoff");
      cleanedAt = ChannelReliabilityValues.instant(cleanedAt, "cleanedAt");
      if (cutoff.isAfter(cleanedAt)) {
        throw new IllegalArgumentException("Cleanup Cutoff 不能晚于执行时间");
      }
      requireBatch(batchSize);
      if (maxInboxRecords < 1 || maxDeliveryRecords < 1) {
        throw new IllegalArgumentException("Ledger 容量必须为正数");
      }
    }
  }

  private static void requireBatch(int value) {
    if (value < 1 || value > 1_000) {
      throw new IllegalArgumentException("Ledger Batch Size 必须在 1..1000");
    }
  }
}
