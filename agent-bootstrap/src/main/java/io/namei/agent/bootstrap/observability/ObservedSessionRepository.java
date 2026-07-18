package io.namei.agent.bootstrap.observability;

import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import io.namei.agent.kernel.port.SessionRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ObservedSessionRepository implements SessionRepository {
  private static final Logger logger = LoggerFactory.getLogger(ObservedSessionRepository.class);

  private final SessionRepository delegate;

  public ObservedSessionRepository(SessionRepository delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public SessionSnapshot load(String sessionId) {
    return observe(() -> delegate.load(sessionId));
  }

  @Override
  public void appendTurn(String sessionId, PersistedTurn turn) {
    observe(
        () -> {
          delegate.appendTurn(sessionId, turn);
          return null;
        });
  }

  @Override
  public boolean appendTurnIfNextSequence(
      String sessionId, long expectedNextSequence, PersistedTurn turn) {
    return observe(() -> delegate.appendTurnIfNextSequence(sessionId, expectedNextSequence, turn));
  }

  @Override
  public boolean appendPendingTurnIfNextSequence(
      PersistedTurn pendingTurn, PendingTurnAnchor anchor) {
    return observe(() -> delegate.appendPendingTurnIfNextSequence(pendingTurn, anchor));
  }

  @Override
  public Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
    return observe(() -> delegate.findPendingTurnAnchor(operationReference));
  }

  private <T> T observe(Supplier<T> action) {
    long startedNanos = System.nanoTime();
    RuntimeException failure = null;
    try {
      return action.get();
    } catch (RuntimeException exception) {
      failure = exception;
      throw exception;
    } finally {
      logger
          .atInfo()
          .addKeyValue(
              "databaseLatencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))
          .addKeyValue("outcome", failure == null ? "success" : "failure")
          .addKeyValue("errorCode", failure == null ? "none" : failure.getClass().getSimpleName())
          .log("session database operation completed");
    }
  }
}
