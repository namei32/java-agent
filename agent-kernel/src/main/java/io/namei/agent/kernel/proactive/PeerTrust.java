package io.namei.agent.kernel.proactive;

/** P0 can model fixtures only; local process and remote trust are explicitly not represented. */
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
