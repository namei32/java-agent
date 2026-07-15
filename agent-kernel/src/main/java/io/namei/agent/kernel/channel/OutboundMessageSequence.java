package io.namei.agent.kernel.channel;

public final class OutboundMessageSequence {
  private final String turnId;
  private final String sessionId;
  private final MessageRoute route;
  private State state = State.NEW;
  private long nextSequence;

  public OutboundMessageSequence(InboundMessage inbound) {
    if (inbound == null) {
      throw new IllegalArgumentException("inbound 不能为空");
    }
    this.turnId = inbound.turnId();
    this.sessionId = inbound.sessionId();
    this.route = inbound.route();
  }

  public synchronized OutboundMessage started() {
    if (state != State.NEW) {
      throw new IllegalStateException("Turn 已经开始或结束");
    }
    OutboundMessage message = OutboundMessage.started(turnId, sessionId, route);
    state = State.ACTIVE;
    nextSequence = 1;
    return message;
  }

  public synchronized OutboundMessage delta(String content) {
    requireActive();
    OutboundMessage message =
        OutboundMessage.delta(turnId, sessionId, route, nextSequence, content);
    nextSequence = increment(nextSequence);
    return message;
  }

  public synchronized OutboundMessage completed(String content) {
    requireActive();
    OutboundMessage message =
        OutboundMessage.completed(turnId, sessionId, route, nextSequence, content);
    state = State.TERMINAL;
    return message;
  }

  public synchronized OutboundMessage cancelled(TurnCancellationCode code) {
    requireActive();
    OutboundMessage message =
        OutboundMessage.cancelled(turnId, sessionId, route, nextSequence, code);
    state = State.TERMINAL;
    return message;
  }

  public synchronized OutboundMessage failed(TurnFailureCode code) {
    requireActive();
    OutboundMessage message = OutboundMessage.failed(turnId, sessionId, route, nextSequence, code);
    state = State.TERMINAL;
    return message;
  }

  public synchronized boolean isTerminal() {
    return state == State.TERMINAL;
  }

  private void requireActive() {
    if (state != State.ACTIVE) {
      throw new IllegalStateException("Turn 尚未开始或已经结束");
    }
  }

  private static long increment(long sequence) {
    if (sequence == Long.MAX_VALUE) {
      throw new IllegalStateException("出站消息序号已耗尽");
    }
    return sequence + 1;
  }

  private enum State {
    NEW,
    ACTIVE,
    TERMINAL
  }
}
