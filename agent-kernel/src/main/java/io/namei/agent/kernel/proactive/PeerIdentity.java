package io.namei.agent.kernel.proactive;

import java.util.Objects;
import java.util.regex.Pattern;

/** An opaque local Fake identity; it has no URL, command, environment, or Agent Card. */
public record PeerIdentity(PeerTrust trust, String peerRef) {
  private static final Pattern REFERENCE = Pattern.compile("[a-z][a-z0-9-]{0,62}");

  public PeerIdentity {
    trust = Objects.requireNonNull(trust, "trust");
    if (trust != PeerTrust.LOCAL_FAKE || peerRef == null || !REFERENCE.matcher(peerRef).matches()) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }

  @Override
  public String toString() {
    return "PeerIdentity[trust=" + trust + ", peerRef=<redacted>]";
  }
}
