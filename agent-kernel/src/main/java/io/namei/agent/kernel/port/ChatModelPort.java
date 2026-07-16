package io.namei.agent.kernel.port;

import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import java.util.Objects;

@FunctionalInterface
public interface ChatModelPort {
  ChatModelResponse generate(ChatModelRequest request);

  default ChatModelResponse generate(
      ChatModelRequest request, ChatModelStreamObserver observer, CancellationSignal cancellation) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(observer, "observer");
    Objects.requireNonNull(cancellation, "cancellation");
    cancellation.throwIfCancellationRequested();
    return generate(request);
  }
}
