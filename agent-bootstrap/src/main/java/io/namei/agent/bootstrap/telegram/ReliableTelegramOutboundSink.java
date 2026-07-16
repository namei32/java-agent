package io.namei.agent.bootstrap.telegram;

import io.namei.agent.application.BoundedOutboundBuffer;
import io.namei.agent.application.ChannelDeliveryWakeSignal;
import io.namei.agent.application.DurableTerminalCoordinator;
import io.namei.agent.application.OutboundMessageSink;
import io.namei.agent.application.ReliableTurnContext;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public final class ReliableTelegramOutboundSink implements OutboundMessageSink {
  private final BoundedOutboundBuffer local;
  private final DurableTerminalCoordinator durable;

  public ReliableTelegramOutboundSink(
      ChannelLedgerPort ledger,
      ReliableTurnContext context,
      String chunkAlgorithm,
      TelegramTerminalRenderer projector,
      ChannelDeliveryWakeSignal wake,
      Clock clock,
      int bufferCapacity,
      Duration publishTimeout) {
    this.local = new BoundedOutboundBuffer(context.inbound(), bufferCapacity, publishTimeout);
    this.durable =
        new DurableTerminalCoordinator(
            ledger,
            context,
            chunkAlgorithm,
            Objects.requireNonNull(projector, "projector"),
            this::publishLocal,
            wake,
            clock);
  }

  @Override
  public void publish(OutboundMessage message) {
    durable.publish(message);
  }

  private void publishLocal(OutboundMessage message) {
    local.publish(message);
    if (local.poll(Duration.ZERO).isEmpty()) {
      throw new IllegalStateException("Telegram 本地生命周期消息缺失");
    }
  }
}
