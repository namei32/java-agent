package io.namei.agent.kernel.proactive;

public enum PeerTaskState {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }

  public static PeerTaskState parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }
}
