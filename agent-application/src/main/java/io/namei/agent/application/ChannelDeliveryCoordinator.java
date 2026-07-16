package io.namei.agent.application;

import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureCarrier;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureKind;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.DeliveryAttemptOutcome;
import io.namei.agent.kernel.channel.reliability.DeliveryPartState;
import io.namei.agent.kernel.channel.reliability.DeliveryState;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class ChannelDeliveryCoordinator implements ChannelDeliveryProcessor {
  private static final String RETRY_BUDGET_EXCEEDED = "RETRY_BUDGET_EXCEEDED";
  private static final String TRANSPORT_OUTCOME_UNKNOWN = "TRANSPORT_OUTCOME_UNKNOWN";

  private final ChannelLedgerPort ledger;
  private final ChannelDeliveryTransport transport;
  private final Clock clock;
  private final ChannelInstanceId instance;
  private final String ownerId;
  private final ChannelDeliverySettings settings;

  public ChannelDeliveryCoordinator(
      ChannelLedgerPort ledger,
      ChannelDeliveryTransport transport,
      Clock clock,
      ChannelInstanceId instance,
      String ownerId,
      ChannelDeliverySettings settings) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.instance = Objects.requireNonNull(instance, "instance");
    this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public ChannelDeliveryStep processNext() {
    Instant claimedAt = clock.instant();
    Optional<ChannelLedgerResult.DeliveryWork> claimed =
        claim(
            new ChannelLedgerCommand.ClaimDelivery(
                instance, ownerId, claimedAt.plus(settings.lease()), claimedAt));
    if (claimed.isEmpty()) {
      return ChannelDeliveryStep.empty();
    }

    ChannelLedgerResult.DeliveryWork work = claimed.orElseThrow();
    ChannelDeliveryResult result;
    try {
      result =
          Objects.requireNonNull(
              transport.send(new ChannelDeliveryRequest(work.targetId(), work.payload())),
              "transport 返回了 null");
    } catch (RuntimeException failure) {
      result = new ChannelDeliveryResult.Unknown(TRANSPORT_OUTCOME_UNKNOWN);
    }

    Instant completedAt = clock.instant();
    Outcome outcome = outcome(result, completedAt);
    ChannelLedgerResult.DeliveryUpdate update =
        record(
            new ChannelLedgerCommand.RecordDeliveryOutcome(
                work.deliveryId(),
                work.partIndex(),
                work.attemptNumber(),
                work.ownerId(),
                outcome.kind(),
                outcome.remoteMessageId(),
                outcome.retryAt(),
                outcome.errorCode(),
                completedAt));
    return step(update, outcome.retryAt());
  }

  private Outcome outcome(ChannelDeliveryResult result, Instant completedAt) {
    return switch (result) {
      case ChannelDeliveryResult.Confirmed confirmed ->
          new Outcome(DeliveryAttemptOutcome.SUCCEEDED, confirmed.remoteMessageId(), null, "");
      case ChannelDeliveryResult.Retryable retryable -> {
        Duration delay = retryable.retryAfter();
        if (delay.isZero() || delay.isNegative() || delay.compareTo(settings.maxRetryAfter()) > 0) {
          yield new Outcome(
              DeliveryAttemptOutcome.PERMANENT_REJECTED, null, null, RETRY_BUDGET_EXCEEDED);
        }
        yield new Outcome(
            DeliveryAttemptOutcome.RETRYABLE_REJECTED,
            null,
            completedAt.plus(delay),
            retryable.code());
      }
      case ChannelDeliveryResult.Permanent permanent ->
          new Outcome(DeliveryAttemptOutcome.PERMANENT_REJECTED, null, null, permanent.code());
      case ChannelDeliveryResult.Unknown unknown ->
          new Outcome(DeliveryAttemptOutcome.UNKNOWN, null, null, unknown.code());
    };
  }

  private Optional<ChannelLedgerResult.DeliveryWork> claim(
      ChannelLedgerCommand.ClaimDelivery command) {
    try {
      return Objects.requireNonNull(ledger.claimNextDelivery(command), "ledger 返回了 null");
    } catch (RuntimeException failure) {
      throw mapLedgerFailure(failure);
    }
  }

  private ChannelLedgerResult.DeliveryUpdate record(
      ChannelLedgerCommand.RecordDeliveryOutcome command) {
    try {
      return Objects.requireNonNull(ledger.recordDeliveryOutcome(command), "ledger 返回了 null");
    } catch (RuntimeException failure) {
      throw mapLedgerFailure(failure);
    }
  }

  private static ChannelDeliveryStep step(
      ChannelLedgerResult.DeliveryUpdate update, Instant requestedRetryAt) {
    if (update.state() == DeliveryState.DELIVERED
        && update.partState() == DeliveryPartState.DELIVERED) {
      return ChannelDeliveryStep.delivered();
    }
    if (update.state() == DeliveryState.PENDING
        && update.partState() == DeliveryPartState.DELIVERED) {
      return ChannelDeliveryStep.partDelivered();
    }
    if (update.state() == DeliveryState.PENDING
        && update.partState() == DeliveryPartState.RETRY_WAIT
        && requestedRetryAt != null) {
      return ChannelDeliveryStep.retryScheduled(requestedRetryAt);
    }
    if (update.state() == DeliveryState.FAILED && update.partState() == DeliveryPartState.FAILED) {
      return ChannelDeliveryStep.failed();
    }
    if (update.state() == DeliveryState.UNKNOWN
        && update.partState() == DeliveryPartState.UNKNOWN) {
      return ChannelDeliveryStep.unknown();
    }
    throw new ReliableChannelException(ReliableChannelFailure.LEDGER_UNAVAILABLE);
  }

  private static ReliableChannelException mapLedgerFailure(RuntimeException failure) {
    if (failure instanceof ReliableChannelException reliable) {
      return reliable;
    }
    if (failure instanceof ChannelLedgerFailureCarrier carrier) {
      ChannelLedgerFailureKind kind = carrier.ledgerFailureKind();
      return new ReliableChannelException(
          switch (kind) {
            case UNAVAILABLE -> ReliableChannelFailure.LEDGER_UNAVAILABLE;
            case IDEMPOTENCY_CONFLICT -> ReliableChannelFailure.IDEMPOTENCY_CONFLICT;
            case CAPACITY_EXCEEDED -> ReliableChannelFailure.LEDGER_CAPACITY_EXCEEDED;
            case STALE_WRITE -> ReliableChannelFailure.LEDGER_STALE_WRITE;
          });
    }
    return new ReliableChannelException(ReliableChannelFailure.LEDGER_UNAVAILABLE);
  }

  private record Outcome(
      DeliveryAttemptOutcome kind, String remoteMessageId, Instant retryAt, String errorCode) {}
}
