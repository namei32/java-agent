package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureCarrier;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureKind;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.DeliveryMessageType;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableTerminalCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-16T07:00:00Z");
  private static final ChannelInstanceId INSTANCE = ChannelInstanceId.derive("telegram", "100001");
  private static final InboundMessage INBOUND =
      new InboundMessage(
          MessageContract.CURRENT_VERSION,
          "message-1",
          "turn-1",
          "telegram:10001",
          new MessageRoute("telegram", "10001"),
          "10001",
          "question",
          NOW);
  private static final ReliableTurnContext CONTEXT =
      new ReliableTurnContext(INSTANCE, INBOUND, "10001", 1);

  @Test
  void keepsProgressVolatileButCommitsTerminalBeforeWakeAndLocalNotification() {
    var order = new ArrayList<String>();
    var ledger = new TerminalLedger(order);
    var local = new ArrayList<OutboundMessage>();
    var coordinator = coordinator(ledger, local, () -> order.add("wake"));
    OutboundMessage started = OutboundMessage.started("turn-1", "telegram:10001", INBOUND.route());
    OutboundMessage delta =
        OutboundMessage.delta("turn-1", "telegram:10001", INBOUND.route(), 1, "draft");
    OutboundMessage terminal =
        OutboundMessage.completed("turn-1", "telegram:10001", INBOUND.route(), 2, "answer");

    coordinator.publish(started);
    coordinator.publish(delta);

    assertThat(ledger.terminals).isEmpty();
    assertThat(local).containsExactly(started, delta);

    coordinator.publish(terminal);

    assertThat(order)
        .containsExactly(
            "local-TURN_STARTED", "local-CONTENT_DELTA", "ledger", "wake", "local-TURN_COMPLETED");
    assertThat(ledger.terminals).hasSize(1);
    ChannelLedgerCommand.RecordTerminal command = ledger.terminals.getFirst();
    assertThat(command.turnId()).isEqualTo("turn-1");
    assertThat(command.expectedRevision()).isOne();
    assertThat(command.delivery().messageType()).isEqualTo(DeliveryMessageType.TURN_COMPLETED);
    assertThat(command.delivery().parts())
        .extracting(part -> part.payload())
        .containsExactly("rendered:answer");
    assertThat(local).containsExactly(started, delta, terminal);
  }

  @Test
  void commitFailurePreventsWakeAndTerminalNotification() {
    var order = new ArrayList<String>();
    var ledger = new TerminalLedger(order);
    ledger.failure = new ClassifiedFailure(ChannelLedgerFailureKind.UNAVAILABLE);
    var local = new ArrayList<OutboundMessage>();
    var wakes = new AtomicInteger();
    var coordinator = coordinator(ledger, local, wakes::incrementAndGet);
    OutboundMessage started = OutboundMessage.started("turn-1", "telegram:10001", INBOUND.route());
    OutboundMessage terminal =
        OutboundMessage.completed("turn-1", "telegram:10001", INBOUND.route(), 1, "answer");
    coordinator.publish(started);

    assertThatThrownBy(() -> coordinator.publish(terminal))
        .isInstanceOf(OutboundDeliveryException.class)
        .extracting(failure -> ((OutboundDeliveryException) failure).reason())
        .isEqualTo(OutboundDeliveryException.Reason.DURABLE_COMMIT_FAILED);
    assertThat(wakes).hasValue(0);
    assertThat(local).containsExactly(started);
  }

  @Test
  void matchingTerminalReplaysAndLostWakeCannotOverrideDurableCommit() {
    var ledger = new TerminalLedger(new ArrayList<>());
    ledger.results.add(
        new ChannelLedgerResult.Terminal(
            ChannelLedgerResult.TerminalStatus.CREATED, "delivery-fixed", 2));
    ledger.results.add(
        new ChannelLedgerResult.Terminal(
            ChannelLedgerResult.TerminalStatus.REPLAYED, "delivery-fixed", 2));
    var coordinator =
        coordinator(
            ledger,
            new ArrayList<>(),
            () -> {
              throw new IllegalStateException("lost-wake");
            });
    OutboundMessage terminal =
        OutboundMessage.completed("turn-1", "telegram:10001", INBOUND.route(), 1, "answer");

    ChannelLedgerResult.Terminal created = coordinator.recordTerminal(terminal);
    ChannelLedgerResult.Terminal replayed = coordinator.recordTerminal(terminal);

    assertThat(created.status()).isEqualTo(ChannelLedgerResult.TerminalStatus.CREATED);
    assertThat(replayed.status()).isEqualTo(ChannelLedgerResult.TerminalStatus.REPLAYED);
    assertThat(ledger.terminals).hasSize(2);
    assertThat(ledger.terminals)
        .extracting(command -> command.delivery().payloadFingerprint())
        .containsOnly(ledger.terminals.getFirst().delivery().payloadFingerprint());

    ledger.failure = new ClassifiedFailure(ChannelLedgerFailureKind.IDEMPOTENCY_CONFLICT);
    OutboundMessage conflict =
        OutboundMessage.completed("turn-1", "telegram:10001", INBOUND.route(), 1, "changed");
    assertThatThrownBy(() -> coordinator.recordTerminal(conflict))
        .isInstanceOf(OutboundDeliveryException.class)
        .extracting(failure -> ((OutboundDeliveryException) failure).reason())
        .isEqualTo(OutboundDeliveryException.Reason.TERMINAL_CONFLICT);
  }

  private static DurableTerminalCoordinator coordinator(
      TerminalLedger ledger, List<OutboundMessage> local, ChannelDeliveryWakeSignal wake) {
    return new DurableTerminalCoordinator(
        ledger,
        CONTEXT,
        "telegram-text-chunks-v1",
        terminal -> List.of("rendered:" + terminal.content()),
        message -> {
          ledger.order.add("local-" + message.type());
          local.add(message);
        },
        wake,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

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

  private static final class TerminalLedger implements ChannelLedgerPort {
    private final List<String> order;
    private final List<ChannelLedgerCommand.RecordTerminal> terminals = new ArrayList<>();
    private final ArrayDeque<ChannelLedgerResult.Terminal> results = new ArrayDeque<>();
    private RuntimeException failure;

    private TerminalLedger(List<String> order) {
      this.order = order;
    }

    @Override
    public ChannelLedgerResult.Terminal recordTerminal(
        ChannelLedgerCommand.RecordTerminal command) {
      order.add("ledger");
      terminals.add(command);
      if (failure != null) {
        throw failure;
      }
      return results.isEmpty()
          ? new ChannelLedgerResult.Terminal(
              ChannelLedgerResult.TerminalStatus.CREATED, command.delivery().deliveryId(), 2)
          : results.removeFirst();
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
    public Optional<ChannelLedgerResult.DeliveryWork> claimNextDelivery(
        ChannelLedgerCommand.ClaimDelivery command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChannelLedgerResult.DeliveryUpdate recordDeliveryOutcome(
        ChannelLedgerCommand.RecordDeliveryOutcome command) {
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
