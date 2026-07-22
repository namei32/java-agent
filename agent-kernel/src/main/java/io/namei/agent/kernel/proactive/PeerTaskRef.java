package io.namei.agent.kernel.proactive;

import java.util.regex.Pattern;

/** 不透明的 Task 引用，不是 URL、进程标识符或外部 A2A Task ID。 */
public record PeerTaskRef(String value) {
  private static final Pattern VALID = Pattern.compile("peer-task-[a-z0-9][a-z0-9-]{0,52}");

  public PeerTaskRef {
    if (value == null || !VALID.matcher(value).matches()) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }

  public static PeerTaskRef parse(String value) {
    return new PeerTaskRef(value);
  }

  @Override
  public String toString() {
    return "PeerTaskRef[<redacted>]";
  }
}
