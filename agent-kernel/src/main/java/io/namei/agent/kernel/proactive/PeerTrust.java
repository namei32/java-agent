package io.namei.agent.kernel.proactive;

/** P0 只能建模 Fixture；明确不表示本地进程和远程信任。 */
public enum PeerTrust {
  LOCAL_FAKE;

  public static PeerTrust parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }
}
