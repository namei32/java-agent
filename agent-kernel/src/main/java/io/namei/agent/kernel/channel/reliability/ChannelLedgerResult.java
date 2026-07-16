package io.namei.agent.kernel.channel.reliability;

import java.time.Instant;
import java.util.Objects;

public final class ChannelLedgerResult {
  private ChannelLedgerResult() {}

  public enum InboxStatus {
    RESERVED_NEW,
    START_RETRYABLE,
    IN_PROGRESS,
    ALREADY_TERMINAL,
    EXECUTION_UNKNOWN,
    EVENT_RECORDED,
    FEEDBACK_QUEUED
  }

  public enum TurnStartStatus {
    STARTED,
    STALE
  }

  public enum TerminalStatus {
    CREATED,
    REPLAYED
  }

  public record Event(
      InboxStatus status, String turnId, long revision, String deliveryId, long nextSequence) {
    public Event {
      Objects.requireNonNull(status, "status");
      turnId = ChannelReliabilityValues.optionalIdentifier(turnId, "Turn ID", 128);
      deliveryId = ChannelReliabilityValues.optionalIdentifier(deliveryId, "Delivery ID", 128);
      ChannelReliabilityValues.nonNegative(revision, "revision");
      ChannelReliabilityValues.nonNegative(nextSequence, "nextSequence");
    }

    @Override
    public String toString() {
      return "LedgerEventResult[status=" + status + ", sensitiveFields=<redacted>]";
    }
  }

  public record TurnStart(TurnStartStatus status, long revision, long nextSequence) {
    public TurnStart {
      Objects.requireNonNull(status, "status");
      ChannelReliabilityValues.nonNegative(revision, "revision");
      ChannelReliabilityValues.nonNegative(nextSequence, "nextSequence");
    }
  }

  public record Terminal(TerminalStatus status, String deliveryId, long revision) {
    public Terminal {
      Objects.requireNonNull(status, "status");
      deliveryId = ChannelReliabilityValues.identifier(deliveryId, "Delivery ID", 128);
      ChannelReliabilityValues.nonNegative(revision, "revision");
    }

    @Override
    public String toString() {
      return "LedgerTerminalResult[status=" + status + ", sensitiveFields=<redacted>]";
    }
  }

  public record DeliveryWork(
      ChannelInstanceId instance,
      String deliveryId,
      String targetId,
      int partIndex,
      String payload,
      int attemptNumber,
      String ownerId,
      long revision,
      Instant claimedAt) {
    public DeliveryWork {
      Objects.requireNonNull(instance, "instance");
      deliveryId = ChannelReliabilityValues.identifier(deliveryId, "Delivery ID", 128);
      targetId = ChannelReliabilityValues.identifier(targetId, "Delivery Target", 256);
      if (partIndex < 0 || partIndex >= ChannelReliabilityValues.MAX_PARTS) {
        throw new IllegalArgumentException("Delivery Part Index 超出范围");
      }
      payload = ChannelReliabilityValues.payload(payload);
      if (attemptNumber < 1 || attemptNumber > ChannelReliabilityValues.MAX_DELIVERY_ATTEMPTS) {
        throw new IllegalArgumentException("Delivery Attempt 超出范围");
      }
      ownerId = ChannelReliabilityValues.ownerId(ownerId);
      ChannelReliabilityValues.nonNegative(revision, "revision");
      claimedAt = ChannelReliabilityValues.instant(claimedAt, "claimedAt");
    }

    @Override
    public String toString() {
      return "DeliveryWork[partIndex="
          + partIndex
          + ", attemptNumber="
          + attemptNumber
          + ", sensitiveFields=<redacted>]";
    }
  }

  public record DeliveryUpdate(
      DeliveryState state, DeliveryPartState partState, int nextPartIndex, long revision) {
    public DeliveryUpdate {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(partState, "partState");
      if (nextPartIndex < 0 || nextPartIndex > ChannelReliabilityValues.MAX_PARTS) {
        throw new IllegalArgumentException("Next Part Index 超出范围");
      }
      ChannelReliabilityValues.nonNegative(revision, "revision");
    }
  }

  public record Recovery(int processed, boolean remaining) {
    public Recovery {
      ChannelReliabilityValues.nonNegative(processed, "processed");
    }
  }

  public record Cleanup(int processed, boolean capacityAvailable) {
    public Cleanup {
      ChannelReliabilityValues.nonNegative(processed, "processed");
    }
  }

  public record Snapshot(
      long nextSequence,
      long pendingDeliveries,
      long unknownExecutions,
      long unknownDeliveries,
      String stableErrorCode) {
    public Snapshot {
      ChannelReliabilityValues.nonNegative(nextSequence, "nextSequence");
      ChannelReliabilityValues.nonNegative(pendingDeliveries, "pendingDeliveries");
      ChannelReliabilityValues.nonNegative(unknownExecutions, "unknownExecutions");
      ChannelReliabilityValues.nonNegative(unknownDeliveries, "unknownDeliveries");
      stableErrorCode = ChannelReliabilityValues.decisionCode(stableErrorCode, false);
    }
  }
}
