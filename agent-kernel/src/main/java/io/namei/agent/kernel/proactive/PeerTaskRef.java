package io.namei.agent.kernel.proactive;

import java.util.regex.Pattern;

/** Opaque task reference. It is not a URL, process identifier, or external A2A task ID. */
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
