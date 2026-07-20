package io.namei.agent.kernel.proactive;

import java.util.Objects;

/**
 * The one immutable peer description P4 can represent; it contains no endpoint or launcher data.
 */
public record LocalFakePeerManifest(
    PeerIdentity identity,
    String protocol,
    int contractVersion,
    LocalFakePeerResourceBudget resources) {
  public static final String PEER_REF = "peer-local-fake";
  public static final String PROTOCOL = "local-fake-peer-v1";
  public static final int CONTRACT_VERSION = 1;

  public LocalFakePeerManifest {
    identity = Objects.requireNonNull(identity, "identity");
    protocol = Objects.requireNonNull(protocol, "protocol");
    resources = Objects.requireNonNull(resources, "resources");
    if (identity.trust() != PeerTrust.LOCAL_FAKE
        || !PEER_REF.equals(identity.peerRef())
        || !PROTOCOL.equals(protocol)
        || contractVersion != CONTRACT_VERSION
        || !resources.equals(LocalFakePeerResourceBudget.fixed())) {
      throw ProactiveContract.violation(ProactiveStableCode.PEER_CONTRACT_INVALID);
    }
  }

  public static LocalFakePeerManifest approved() {
    return new LocalFakePeerManifest(
        new PeerIdentity(PeerTrust.LOCAL_FAKE, PEER_REF),
        PROTOCOL,
        CONTRACT_VERSION,
        LocalFakePeerResourceBudget.fixed());
  }

  @Override
  public String toString() {
    return "LocalFakePeerManifest[identity=<redacted>, protocol="
        + protocol
        + ", contractVersion="
        + contractVersion
        + ", resources="
        + resources
        + "]";
  }
}
