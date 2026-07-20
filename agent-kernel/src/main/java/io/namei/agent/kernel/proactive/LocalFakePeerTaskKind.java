package io.namei.agent.kernel.proactive;

/** P4 exposes one body-free local fake task, not an arbitrary peer prompt or command. */
public enum LocalFakePeerTaskKind {
  LOCAL_FAKE_TASK;

  public static LocalFakePeerTaskKind parse(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }
}
