package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import java.util.Objects;

public interface SessionRepository {
  SessionSnapshot load(String sessionId);

  void appendTurn(String sessionId, PersistedTurn turn);

  /**
   * Atomically appends a complete turn only when the persisted sequence is still {@code
   * expectedNextSequence}.
   *
   * <p>The default fails closed so wrappers and test doubles cannot silently replace SQLite's
   * compare-and-set semantics.
   */
  default boolean appendTurnIfNextSequence(
      String sessionId, long expectedNextSequence, PersistedTurn turn) {
    Objects.requireNonNull(sessionId, "sessionId");
    if (expectedNextSequence < 0) {
      throw new IllegalArgumentException("预期 Session 序号不能为负数");
    }
    Objects.requireNonNull(turn, "turn");
    throw new UnsupportedOperationException("Session Repository 不支持条件追加");
  }
}
