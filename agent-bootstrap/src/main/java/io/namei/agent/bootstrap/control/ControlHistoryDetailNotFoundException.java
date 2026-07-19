package io.namei.agent.bootstrap.control;

/**
 * Intentionally indistinguishable absence for scope, actor, ref, cursor, expiry, and revocation.
 */
final class ControlHistoryDetailNotFoundException extends RuntimeException {
  ControlHistoryDetailNotFoundException() {
    super("控制历史详情不存在");
  }
}
