package io.namei.agent.kernel.port;

import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnResolution;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.model.SessionSnapshot;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话历史与待恢复轮次的持久化端口。
 *
 * <p>实现必须保持一个完整 Turn 的原子性，不能让用户消息和助手消息分别可见。带条件的方法用于可靠投递和崩溃恢复，默认关闭式失败以防普通实现悄悄丢失 CAS 语义。
 */
public interface SessionRepository {
  /**
   * 读取指定会话的当前一致性快照。
   *
   * @param sessionId 内部会话标识
   * @return 不可变会话快照；会话不存在时返回空快照而不是 {@code null}
   */
  SessionSnapshot load(String sessionId);

  /**
   * 原子追加一轮已经完成的用户/助手消息。
   *
   * @param sessionId 内部会话标识
   * @param turn 需要提交的完整轮次
   */
  void appendTurn(String sessionId, PersistedTurn turn);

  /**
   * 仅在持久序列仍为 {@code expectedNextSequence} 时原子追加一个完整 Turn。
   *
   * <p>默认实现关闭式失败，避免 Wrapper 和测试替身静默替换 SQLite 的比较后设置语义。
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
   * 原子持久化一个完整的初始 Pending Turn 及其内部 Session Anchor。
   *
   * <p>默认实现关闭式失败。不得通过组合普通追加操作与后续 Anchor 写入来实现。
   */
  default boolean appendPendingTurnIfNextSequence(
      PersistedTurn pendingTurn, PendingTurnAnchor anchor) {
    Objects.requireNonNull(pendingTurn, "pendingTurn");
    Objects.requireNonNull(anchor, "anchor");
    throw new UnsupportedOperationException("Session Repository 不支持 Pending Turn Anchor");
  }

  /** 仅向未来已认证的恢复协调器返回内部 Anchor。 */
  default Optional<PendingTurnAnchor> findPendingTurnAnchor(String operationReference) {
    Objects.requireNonNull(operationReference, "operationReference");
    throw new UnsupportedOperationException("Session Repository 不支持 Pending Turn Anchor");
  }

  /** Conditionally terminally cancels one still-pending Anchor without appending a Message. */
  default boolean cancelPendingTurnAnchorIfMatches(PendingTurnAnchor anchor) {
    Objects.requireNonNull(anchor, "anchor");
    throw new UnsupportedOperationException("Session Repository 不支持 Pending Turn Anchor 取消");
  }

  /**
   * 仅在已存储 Pending Anchor 与 Session Cursor 仍精确匹配时，原子追加一个安全 Assistant Resolution。
   *
   * <p>默认实现关闭式失败。实现绝不能调用 Tool、写入 User 消息，也不能通过分别提交 Session 和 Anchor 来组合该操作。
   */
  default boolean appendPendingResolutionIfAnchorMatches(
      PendingTurnAnchor anchor, PendingTurnResolution resolution) {
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(resolution, "resolution");
    throw new UnsupportedOperationException("Session Repository 不支持 Pending Turn Resolution");
  }
}
