package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnResolution;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import java.util.Objects;
import java.util.Optional;

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

  /**
   * Atomically persists one complete initial pending turn and its internal Session Anchor.
   *
   * <p>The default fails closed. It must not be implemented by composing the ordinary append
   * operation with a later Anchor write.
   */
  default boolean appendPendingTurnIfNextSequence(
      PersistedTurn pendingTurn, PendingTurnAnchor anchor) {
    Objects.requireNonNull(pendingTurn, "pendingTurn");
    Objects.requireNonNull(anchor, "anchor");
    throw new UnsupportedOperationException("Session Repository 不支持 Pending Turn Anchor");
  }

  /** Returns an internal Anchor only to a future authenticated recovery coordinator. */
  default Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
    Objects.requireNonNull(operationReference, "operationReference");
    throw new UnsupportedOperationException("Session Repository 不支持 Pending Turn Anchor");
  }

  /**
   * Atomically appends one safe Assistant resolution only if the stored pending Anchor and Session
   * cursor still exactly match.
   *
   * <p>The default fails closed. Implementations must never invoke a Tool, write a User message, or
   * compose this operation from separate Session and Anchor commits.
   */
  default boolean appendPendingResolutionIfAnchorMatches(
      PendingTurnAnchor anchor, PendingTurnResolution resolution) {
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(resolution, "resolution");
    throw new UnsupportedOperationException("Session Repository 不支持 Pending Turn Resolution");
  }
}
