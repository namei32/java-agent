package io.namei.agent.bootstrap.observability;

import io.namei.agent.kernel.concurrent.CancellationSignal;
import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.ChatModelStreamObserver;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ObservedChatModelPort implements ChatModelPort {
  private static final Logger logger = LoggerFactory.getLogger(ObservedChatModelPort.class);

  private final ChatModelPort delegate;
  private final String modelName;

  public ObservedChatModelPort(ChatModelPort delegate, String modelName) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.modelName = Objects.requireNonNull(modelName, "modelName");
  }

  @Override
  public ChatModelResponse generate(ChatModelRequest request) {
    return observe(request, () -> delegate.generate(request));
  }

  @Override
  public ChatModelResponse generate(
      ChatModelRequest request, ChatModelStreamObserver observer, CancellationSignal cancellation) {
    Objects.requireNonNull(observer, "observer");
    Objects.requireNonNull(cancellation, "cancellation");
    return observe(request, () -> delegate.generate(request, observer, cancellation));
  }

  private ChatModelResponse observe(
      ChatModelRequest request, Supplier<ChatModelResponse> invocation) {
    Objects.requireNonNull(request, "request");
    long startedNanos = System.nanoTime();
    RuntimeException failure = null;
    try {
      return invocation.get();
    } catch (RuntimeException exception) {
      failure = exception;
      throw exception;
    } finally {
      logger
          .atInfo()
          .addKeyValue("model", modelName)
          .addKeyValue("historyMessageCount", request.messages().size())
          .addKeyValue(
              "modelLatencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))
          .addKeyValue("outcome", failure == null ? "success" : "failure")
          .addKeyValue("errorCode", failure == null ? "none" : failure.getClass().getSimpleName())
          .log("model request completed");
    }
  }
}
