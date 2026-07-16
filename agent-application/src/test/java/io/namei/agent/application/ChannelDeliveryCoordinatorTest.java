package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelDeliveryCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");
  private static final ChannelInstanceId INSTANCE = ChannelInstanceId.derive("telegram", "100001");
  private static final String OWNER = "1".repeat(32);
  private static final ChannelDeliverySettings SETTINGS =
      new ChannelDeliverySettings(Duration.ofMinutes(1), Duration.ofMinutes(2));

  @Test
  void claimsAttemptBeforeCallingNetworkAndCommitsConfirmedReceiptAfterwards() {
    var order = new ArrayList<String>();
    var ledger = new DeliveryLedger(order);
    ledger.works.add(work("delivery-1", 0, 1));
    ledger.updates.add(update(DeliveryState.DELIVERED, DeliveryPartState.DELIVERED));
    var requests = new ArrayList<ChannelDeliveryRequest>();
    var coordinator =
        coordinator(
            ledger,
            request -> {
              order.add("network");
              assertThat(ledger.claimReturned).isTrue();
              requests.add(request);
              return new ChannelDeliveryResult.Confirmed("501");
            });

    ChannelDeliveryStep result = coordinator.processNext();

    assertThat(order).containsExactly("claim", "network", "outcome");
    assertThat(result.status()).isEqualTo(ChannelDeliveryStep.Status.DELIVERED);
    assertThat(requests).containsExactly(new ChannelDeliveryRequest("10001", "payload-0"));
    assertThat(ledger.claims.getFirst().claimedAt()).isEqualTo(NOW);
    assertThat(ledger.claims.getFirst().leaseExpiresAt()).isEqualTo(NOW.plusSeconds(60));
    ChannelLedgerCommand.RecordDeliveryOutcome outcome = ledger.outcomes.getFirst();
    assertThat(outcome.outcome()).isEqualTo(DeliveryAttemptOutcome.SUCCEEDED);
    assertThat(outcome.remoteMessageId()).isEqualTo("501");
    assertThat(outcome.completedAt()).isEqualTo(NOW);
  }

  @Test
  void convertsRateLimitPermanentUnknownAndTransportFailureWithoutGuessing() {
    var cases =
        List.of(
            new OutcomeCase(
                new ChannelDeliveryResult.Retryable(Duration.ofSeconds(30), "RATE_LIMITED"),
                DeliveryAttemptOutcome.RETRYABLE_REJECTED,
                DeliveryState.PENDING,
                DeliveryPartState.RETRY_WAIT,
                ChannelDeliveryStep.Status.RETRY_SCHEDULED,
                NOW.plusSeconds(30),
                "RATE_LIMITED"),
            new OutcomeCase(
                new ChannelDeliveryResult.Permanent("CHAT_NOT_FOUND"),
                DeliveryAttemptOutcome.PERMANENT_REJECTED,
                DeliveryState.FAILED,
                DeliveryPartState.FAILED,
                ChannelDeliveryStep.Status.FAILED,
                null,
                "CHAT_NOT_FOUND"),
            new OutcomeCase(
                new ChannelDeliveryResult.Unknown("SEND_TIMEOUT"),
                DeliveryAttemptOutcome.UNKNOWN,
                DeliveryState.UNKNOWN,
                DeliveryPartState.UNKNOWN,
                ChannelDeliveryStep.Status.UNKNOWN,
                null,
                "SEND_TIMEOUT"));

    for (OutcomeCase example : cases) {
      var ledger = new DeliveryLedger(new ArrayList<>());
      ledger.works.add(work("delivery-" + example.step(), 0, 1));
      ledger.updates.add(update(example.state(), example.partState()));

      ChannelDeliveryStep result =
          coordinator(ledger, ignored -> example.transport()).processNext();

      ChannelLedgerCommand.RecordDeliveryOutcome command = ledger.outcomes.getFirst();
      assertThat(command.outcome()).isEqualTo(example.outcome());
      assertThat(command.retryAt()).isEqualTo(example.retryAt());
      assertThat(command.errorCode()).isEqualTo(example.errorCode());
      assertThat(result.status()).isEqualTo(example.step());
      assertThat(result.retryAt()).isEqualTo(example.retryAt());
    }

    var thrownLedger = new DeliveryLedger(new ArrayList<>());
    thrownLedger.works.add(work("delivery-thrown", 0, 1));
    thrownLedger.updates.add(update(DeliveryState.UNKNOWN, DeliveryPartState.UNKNOWN));
    ChannelDeliveryStep thrown =
        coordinator(
                thrownLedger,
                ignored -> {
                  throw new IllegalStateException("sensitive-transport-detail");
                })
            .processNext();

    assertThat(thrown.status()).isEqualTo(ChannelDeliveryStep.Status.UNKNOWN);
    assertThat(thrownLedger.outcomes.getFirst().outcome())
        .isEqualTo(DeliveryAttemptOutcome.UNKNOWN);
    assertThat(thrownLedger.outcomes.getFirst().errorCode()).isEqualTo("TRANSPORT_OUTCOME_UNKNOWN");
  }

  @Test
  void rejectsInvalidRetryDelayAndHonoursLedgerAttemptBudgetProjection() {
    var invalid = new DeliveryLedger(new ArrayList<>());
    invalid.works.add(work("delivery-invalid", 0, 1));
    invalid.updates.add(update(DeliveryState.FAILED, DeliveryPartState.FAILED));

    ChannelDeliveryStep invalidResult =
        coordinator(
                invalid,
                ignored ->
                    new ChannelDeliveryResult.Retryable(Duration.ofMinutes(3), "RATE_LIMITED"))
            .processNext();

    assertThat(invalid.outcomes.getFirst().outcome())
        .isEqualTo(DeliveryAttemptOutcome.PERMANENT_REJECTED);
    assertThat(invalid.outcomes.getFirst().errorCode()).isEqualTo("RETRY_BUDGET_EXCEEDED");
    assertThat(invalidResult.status()).isEqualTo(ChannelDeliveryStep.Status.FAILED);

    var exhausted = new DeliveryLedger(new ArrayList<>());
    exhausted.works.add(work("delivery-exhausted", 0, 2));
    exhausted.updates.add(update(DeliveryState.FAILED, DeliveryPartState.FAILED));
    ChannelDeliveryStep exhaustedResult =
        coordinator(
                exhausted,
                ignored ->
                    new ChannelDeliveryResult.Retryable(Duration.ofSeconds(10), "RATE_LIMITED"))
            .processNext();

    assertThat(exhausted.outcomes.getFirst().outcome())
        .isEqualTo(DeliveryAttemptOutcome.RETRYABLE_REJECTED);
    assertThat(exhaustedResult.status()).isEqualTo(ChannelDeliveryStep.Status.FAILED);
    assertThat(exhaustedResult.retryAt()).isNull();
  }

  @Test
  void emptyScanSkipsNetworkAndLedgerFailuresStayStable() {
    var ledger = new DeliveryLedger(new ArrayList<>());
    var network = new ArrayList<ChannelDeliveryRequest>();
    ChannelDeliveryStep empty =
        coordinator(
                ledger,
                request -> {
                  network.add(request);
                  return new ChannelDeliveryResult.Confirmed("501");
                })
            .processNext();

    assertThat(empty.status()).isEqualTo(ChannelDeliveryStep.Status.EMPTY);
    assertThat(network).isEmpty();
    assertThat(ledger.outcomes).isEmpty();

    ledger.claimFailure = new ClassifiedFailure(ChannelLedgerFailureKind.CAPACITY_EXCEEDED);
    assertThatThrownBy(
            () ->
                coordinator(ledger, ignored -> new ChannelDeliveryResult.Confirmed("501"))
                    .processNext())
        .isInstanceOf(ReliableChannelException.class)
        .extracting(failure -> ((ReliableChannelException) failure).failure())
        .isEqualTo(ReliableChannelFailure.LEDGER_CAPACITY_EXCEEDED);
  }

  private static ChannelDeliveryCoordinator coordinator(
      DeliveryLedger ledger, ChannelDeliveryTransport transport) {
    return new ChannelDeliveryCoordinator(
        ledger, transport, Clock.fixed(NOW, ZoneOffset.UTC), INSTANCE, OWNER, SETTINGS);
  }

  private static ChannelLedgerResult.DeliveryWork work(
      String deliveryId, int partIndex, int attemptNumber) {
    return new ChannelLedgerResult.DeliveryWork(
        INSTANCE,
        deliveryId,
        "10001",
        partIndex,
        "payload-" + partIndex,
        attemptNumber,
        OWNER,
        1,
        NOW);
  }

  private static ChannelLedgerResult.DeliveryUpdate update(
      DeliveryState state, DeliveryPartState partState) {
    return new ChannelLedgerResult.DeliveryUpdate(state, partState, 1, 2);
  }

  private record OutcomeCase(
      ChannelDeliveryResult transport,
      DeliveryAttemptOutcome outcome,
      DeliveryState state,
      DeliveryPartState partState,
      ChannelDeliveryStep.Status step,
      Instant retryAt,
      String errorCode) {}

  private static final class ClassifiedFailure extends RuntimeException
      implements ChannelLedgerFailureCarrier {
    private final ChannelLedgerFailureKind kind;

    private ClassifiedFailure(ChannelLedgerFailureKind kind) {
      this.kind = kind;
    }

    @Override
    public ChannelLedgerFailureKind ledgerFailureKind() {
      return kind;
    }
  }

  private static final class DeliveryLedger implements ChannelLedgerPort {
    private final List<String> order;
    private final ArrayDeque<ChannelLedgerResult.DeliveryWork> works = new ArrayDeque<>();
    private final ArrayDeque<ChannelLedgerResult.DeliveryUpdate> updates = new ArrayDeque<>();
    private final List<ChannelLedgerCommand.ClaimDelivery> claims = new ArrayList<>();
    private final List<ChannelLedgerCommand.RecordDeliveryOutcome> outcomes = new ArrayList<>();
    private boolean claimReturned;
    private RuntimeException claimFailure;

    private DeliveryLedger(List<String> order) {
      this.order = order;
    }

    @Override
    public Optional<ChannelLedgerResult.DeliveryWork> claimNextDelivery(
        ChannelLedgerCommand.ClaimDelivery command) {
      order.add("claim");
      claims.add(command);
      if (claimFailure != null) {
        throw claimFailure;
      }
      claimReturned = true;
      return Optional.ofNullable(works.pollFirst());
    }

    @Override
    public ChannelLedgerResult.DeliveryUpdate recordDeliveryOutcome(
        ChannelLedgerCommand.RecordDeliveryOutcome command) {
      order.add("outcome");
      outcomes.add(command);
      return updates.removeFirst();
    }

    @Override
    public ChannelLedgerResult.Event recordEvent(ChannelLedgerCommand.RecordEvent command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.TurnStart startTurn(ChannelLedgerCommand.StartTurn command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.Terminal recordTerminal(
        ChannelLedgerCommand.RecordTerminal command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.Recovery recover(ChannelLedgerCommand.Recover command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.Cleanup cleanup(ChannelLedgerCommand.Cleanup command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.Snapshot snapshot(ChannelInstanceId instance) {
      throw new UnsupportedOperationException();
    }
  }
}
