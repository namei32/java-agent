package io.namei.agent.application;

import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageType;
import io.namei.agent.kernel.channel.OutboundSequenceValidator;
import io.namei.agent.kernel.channel.reliability.ChannelFingerprint;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerCommand;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureCarrier;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerFailureKind;
import io.namei.agent.kernel.channel.reliability.ChannelLedgerResult;
import io.namei.agent.kernel.channel.reliability.DeliveryEnvelope;
import io.namei.agent.kernel.channel.reliability.DeliveryMessageType;
import io.namei.agent.kernel.channel.reliability.DeliverySourceKind;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class DurableTerminalCoordinator implements OutboundMessageSink {
  private final ChannelLedgerPort ledger;
  private final ReliableTurnContext context;
  private final String chunkAlgorithm;
  private final ChannelTerminalProjector projector;
  private final OutboundMessageSink local;
  private final ChannelDeliveryWakeSignal wake;
  private final Clock clock;
  private final OutboundSequenceValidator validator;

  public DurableTerminalCoordinator(
      ChannelLedgerPort ledger,
      ReliableTurnContext context,
      String chunkAlgorithm,
      ChannelTerminalProjector projector,
      OutboundMessageSink local,
      ChannelDeliveryWakeSignal wake,
      Clock clock) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.context = Objects.requireNonNull(context, "context");
    this.chunkAlgorithm = requireAlgorithm(chunkAlgorithm);
    this.projector = Objects.requireNonNull(projector, "projector");
    this.local = Objects.requireNonNull(local, "local");
    this.wake = Objects.requireNonNull(wake, "wake");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.validator = new OutboundSequenceValidator(context.inbound());
  }

  @Override
  public synchronized void publish(OutboundMessage message) {
    validateNext(message);
    if (!message.type().isTerminal()) {
      local.publish(message);
      accept(message);
      return;
    }

    recordTerminal(message);
    accept(message);
    try {
      local.publish(message);
    } catch (RuntimeException ignored) {
      // 终态已经进入权威 Outbox；易失本地通知不能反转持久提交。
    }
  }

  public ChannelLedgerResult.Terminal recordTerminal(OutboundMessage terminal) {
    requireTerminalIdentity(terminal);
    List<String> parts;
    try {
      parts = List.copyOf(projector.project(terminal));
    } catch (RuntimeException failure) {
      throw new OutboundDeliveryException(OutboundDeliveryException.Reason.INVALID_MESSAGE);
    }
    DeliveryMessageType messageType = messageType(terminal.type());
    String fingerprint =
        ChannelFingerprint.delivery(
            context.instance(),
            context.targetId(),
            DeliverySourceKind.TURN_TERMINAL,
            terminal.turnId(),
            messageType,
            terminal.code(),
            terminal.retryable(),
            chunkAlgorithm,
            parts);
    DeliveryEnvelope delivery =
        DeliveryEnvelope.create(
            context.instance(),
            "delivery-" + fingerprint,
            context.targetId(),
            DeliverySourceKind.TURN_TERMINAL,
            terminal.turnId(),
            messageType,
            terminal.code(),
            terminal.retryable(),
            chunkAlgorithm,
            parts);
    ChannelLedgerResult.Terminal recorded;
    try {
      recorded =
          Objects.requireNonNull(
              ledger.recordTerminal(
                  new ChannelLedgerCommand.RecordTerminal(
                      context.instance(),
                      terminal.turnId(),
                      context.claimRevision(),
                      delivery,
                      clock.instant())),
              "ledger 返回了 null");
    } catch (RuntimeException failure) {
      throw terminalFailure(failure);
    }
    try {
      wake.signal();
    } catch (RuntimeException ignored) {
      // Worker 启动扫描和每轮账本扫描是权威机制，Wake 只是优化。
    }
    return recorded;
  }

  private void validateNext(OutboundMessage message) {
    try {
      validator.validateNext(message);
    } catch (RuntimeException failure) {
      throw new OutboundDeliveryException(OutboundDeliveryException.Reason.INVALID_MESSAGE);
    }
  }

  private void accept(OutboundMessage message) {
    try {
      validator.accept(message);
    } catch (RuntimeException failure) {
      throw new OutboundDeliveryException(OutboundDeliveryException.Reason.INVALID_MESSAGE);
    }
  }

  private void requireTerminalIdentity(OutboundMessage terminal) {
    if (terminal == null
        || !terminal.type().isTerminal()
        || !context.inbound().turnId().equals(terminal.turnId())
        || !context.inbound().sessionId().equals(terminal.sessionId())
        || !context.inbound().route().equals(terminal.route())) {
      throw new OutboundDeliveryException(OutboundDeliveryException.Reason.INVALID_MESSAGE);
    }
  }

  private static DeliveryMessageType messageType(OutboundMessageType type) {
    return switch (type) {
      case TURN_COMPLETED -> DeliveryMessageType.TURN_COMPLETED;
      case TURN_CANCELLED -> DeliveryMessageType.TURN_CANCELLED;
      case TURN_FAILED -> DeliveryMessageType.TURN_FAILED;
      case TURN_STARTED, CONTENT_DELTA ->
          throw new OutboundDeliveryException(OutboundDeliveryException.Reason.INVALID_MESSAGE);
    };
  }

  private static OutboundDeliveryException terminalFailure(RuntimeException failure) {
    if (failure instanceof OutboundDeliveryException delivery) {
      return delivery;
    }
    if (failure instanceof ChannelLedgerFailureCarrier carrier
        && carrier.ledgerFailureKind() == ChannelLedgerFailureKind.IDEMPOTENCY_CONFLICT) {
      return new OutboundDeliveryException(OutboundDeliveryException.Reason.TERMINAL_CONFLICT);
    }
    return new OutboundDeliveryException(OutboundDeliveryException.Reason.DURABLE_COMMIT_FAILED);
  }

  private static String requireAlgorithm(String value) {
    if (value == null || !value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("分片算法无效");
    }
    return value;
  }
}
