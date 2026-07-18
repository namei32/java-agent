package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.OutboundDeliveryException;
import io.namei.agent.application.ReliableTurnContext;
import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ActiveTurnRegistry;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.application.control.ControlEventHub;
import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.MessageContract;
import io.namei.agent.kernel.channel.MessageRoute;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.control.ControlTerminalKind;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ControlPlaneTelegramIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final ChannelInstanceId INSTANCE =
      ChannelInstanceId.derive("telegram", "bot-test");
  private static final InboundMessage INBOUND =
      new InboundMessage(
          MessageContract.CURRENT_VERSION,
          "message-test",
          "turn-test",
          "telegram:10001",
          new MessageRoute("telegram", "10001"),
          "10001",
          "question",
          NOW);

  @Test
  void durableCommitFailureNeverPublishesAFakeControlTerminal() {
    var reference = controlRef(1);
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var registry = new ActiveTurnRegistry(clock, () -> reference, 1, Duration.ofMinutes(5), 1);
    var hub = new ControlEventHub(registry, clock, 1, 4, Duration.ofMinutes(5));
    var source = new TurnCancellationSource();
    var registration = registry.register("telegram", ControlCancellationHandle.from(source), NOW);
    var subscription = hub.subscribe(reference, "actor-test");
    var ledger = new FailingTerminalLedger();
    var wakes = new AtomicInteger();
    var sink =
        new ReliableTelegramOutboundSink(
            ledger,
            new ReliableTurnContext(INSTANCE, INBOUND, "10001", 1, registration),
            "telegram-text-chunks-v1",
            new TelegramTerminalRenderer(new TelegramTextChunker()),
            wakes::incrementAndGet,
            clock,
            4,
            Duration.ofSeconds(1));
    OutboundMessage started =
        OutboundMessage.started("turn-test", "telegram:10001", INBOUND.route());
    OutboundMessage terminal =
        OutboundMessage.completed("turn-test", "telegram:10001", INBOUND.route(), 1, "answer");

    sink.publish(started);
    assertThat(subscription.poll(Duration.ZERO)).isPresent();

    assertThatThrownBy(() -> sink.publish(terminal))
        .isInstanceOf(OutboundDeliveryException.class)
        .extracting(failure -> ((OutboundDeliveryException) failure).reason())
        .isEqualTo(OutboundDeliveryException.Reason.DURABLE_COMMIT_FAILED);

    assertThat(subscription.poll(Duration.ZERO)).isEmpty();
    assertThat(registry.snapshot().activeTurns())
        .extracting(snapshot -> snapshot.lastSequence())
        .containsExactly(0L);
    assertThat(registry.terminalKind(reference)).isEmpty();
    assertThat(wakes).hasValue(0);

    registration.closeWithoutTerminal();
    assertThat(registry.terminalKind(reference)).contains(ControlTerminalKind.SOURCE_ENDED);
    hub.close();
    registry.close();
  }

  private static io.namei.agent.kernel.control.ControlTurnRef controlRef(int lastByte) {
    byte[] bytes = new byte[16];
    bytes[15] = (byte) lastByte;
    return io.namei.agent.kernel.control.ControlTurnRef.fromBytes(bytes);
  }

  private static final class FailingTerminalLedger implements ChannelLedgerPort {
    @Override
    public ChannelLedgerResult.Terminal recordTerminal(
        ChannelLedgerCommand.RecordTerminal command) {
      throw new IllegalStateException("database-secret");
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
