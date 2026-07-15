package io.namei.agent.application;

import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.OutboundMessage;
import io.namei.agent.kernel.channel.OutboundMessageSequence;
import io.namei.agent.kernel.error.TurnCancelledException;
import java.util.Objects;

public final class MessageTurnService {
  private final ChatUseCase chat;

  public MessageTurnService(ChatUseCase chat) {
    this.chat = Objects.requireNonNull(chat, "chat");
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
          chat.chat(new ChatCommand(inbound.sessionId(), inbound.content()), cancellation);
      if (result == null || !inbound.sessionId().equals(result.sessionId())) {
        throw new IllegalStateException("Chat Result 与入站 Session 不一致");
      }
      OutboundMessage completed = sequence.completed(result.assistant().content());
      sink.publish(completed);
      return completed;
    } catch (OutboundDeliveryException delivery) {
      throw delivery;
    } catch (TurnCancelledException cancelled) {
      OutboundMessage terminal = sequence.cancelled(cancellation.reason());
      sink.publish(terminal);
      return terminal;
    } catch (RuntimeException failure) {
      OutboundMessage terminal = sequence.failed(TurnFailureClassifier.classify(failure));
      sink.publish(terminal);
      return terminal;
    }
  }
}
