package io.namei.agent.bootstrap.observability;

import io.namei.agent.kernel.model.ChatModelRequest;
import io.namei.agent.kernel.model.ChatModelResponse;
import io.namei.agent.kernel.port.ChatModelPort;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
    long startedNanos = System.nanoTime();
    RuntimeException failure = null;
    try {
      return delegate.generate(request);
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
