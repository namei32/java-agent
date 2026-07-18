package io.namei.agent.application;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageSequence;
import io.namei.agent.kernel.error.TurnCancelledException;
import java.util.Objects;

public final class MessageTurnService {
  private final ChatUseCase chat;
  private final OutboundMessageObserver observer;
  private final PromptTurnContextFactory promptContexts;

  public MessageTurnService(ChatUseCase chat) {
    this(chat, OutboundMessageObserver.noop(), null);
  }

  public MessageTurnService(ChatUseCase chat, OutboundMessageObserver observer) {
    this(chat, observer, null);
  }

  public MessageTurnService(
      ChatUseCase chat, OutboundMessageObserver observer, PromptTurnContextFactory promptContexts) {
    this.chat = Objects.requireNonNull(chat, "chat");
    this.observer = Objects.requireNonNull(observer, "observer");
    this.promptContexts = promptContexts;
  }

  public OutboundMessage process(
      InboundMessage inbound, OutboundMessageSink sink, TurnCancellation cancellation) {
    Objects.requireNonNull(inbound, "inbound");
    Objects.requireNonNull(sink, "sink");
    Objects.requireNonNull(cancellation, "cancellation");
    var sequence = new OutboundMessageSequence(inbound);
    sink.publish(sequence.started());

    try {
      if (cancellation.isCancellationRequested()) {
        throw new TurnCancelledException("当前 Turn 已取消");
      }
      ChatResult result =
          chat.chat(command(inbound), cancellation, delta -> sink.publish(sequence.delta(delta)));
      if (result == null || !inbound.sessionId().equals(result.sessionId())) {
        throw new IllegalStateException("Chat Result 与入站 Session 不一致");
      }
      OutboundMessage completed = sequence.completed(result.assistant().content());
      sink.publish(completed);
      return observed(completed);
    } catch (OutboundDeliveryException delivery) {
      throw delivery;
    } catch (TurnCancelledException cancelled) {
      OutboundMessage terminal = sequence.cancelled(cancellation.reason());
      sink.publish(terminal);
      return observed(terminal);
    } catch (RuntimeException failure) {
      OutboundMessage terminal = sequence.failed(TurnFailureClassifier.classify(failure));
      sink.publish(terminal);
      return observed(terminal);
    }
  }

  private ChatCommand command(InboundMessage inbound) {
    if (promptContexts == null) {
      return new ChatCommand(inbound.sessionId(), inbound.content());
    }
    return new ChatCommand(
        inbound.sessionId(),
        inbound.content(),
        promptContexts.create(
            inbound.occurredAt(), inbound.route().channel(), inbound.sessionId()));
  }

  private OutboundMessage observed(OutboundMessage terminal) {
    try {
      observer.onTerminal(terminal);
    } catch (RuntimeException ignored) {
      // 消息观察不能改变已完成的权威投递结果。
    }
    return terminal;
  }
}
