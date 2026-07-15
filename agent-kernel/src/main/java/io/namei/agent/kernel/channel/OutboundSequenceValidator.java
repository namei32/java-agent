package io.namei.agent.kernel.channel;

public final class OutboundSequenceValidator {
  private final String turnId;
  private final String sessionId;
  private final MessageRoute route;
  private long expectedSequence;
  private boolean started;
  private boolean terminal;

  public OutboundSequenceValidator(InboundMessage inbound) {
    if (inbound == null) {
      throw new IllegalArgumentException("inbound 不能为空");
    }
    turnId = inbound.turnId();
    sessionId = inbound.sessionId();
    route = inbound.route();
  }

  public synchronized void validateNext(OutboundMessage message) {
    requireMessage(message);
    if (terminal) {
      throw new IllegalStateException("终态后不能再发布消息");
    }
    if (!turnId.equals(message.turnId())
        || !sessionId.equals(message.sessionId())
        || !route.equals(message.route())) {
      throw new IllegalArgumentException("出站消息身份与 Turn 不一致");
    }
    if (!started && message.type() != OutboundMessageType.TURN_STARTED) {
      throw new IllegalStateException("第一条出站消息必须是 TURN_STARTED");
    }
    if (message.sequence() != expectedSequence) {
      throw new IllegalArgumentException("出站消息 sequence 不连续");
    }
    if (started && message.type() == OutboundMessageType.TURN_STARTED) {
      throw new IllegalStateException("TURN_STARTED 只能出现一次");
    }
  }

  public synchronized void accept(OutboundMessage message) {
    validateNext(message);
    if (message.type() == OutboundMessageType.TURN_STARTED) {
      started = true;
    }
    if (message.type().isTerminal()) {
      terminal = true;
    } else {
      expectedSequence = increment(expectedSequence);
    }
  }

  public synchronized boolean isTerminal() {
    return terminal;
  }

  private static void requireMessage(OutboundMessage message) {
    if (message == null) {
      throw new IllegalArgumentException("message 不能为空");
    }
  }

  private static long increment(long sequence) {
    if (sequence == Long.MAX_VALUE) {
      throw new IllegalStateException("出站消息序号已耗尽");
    }
    return sequence + 1;
  }
}
