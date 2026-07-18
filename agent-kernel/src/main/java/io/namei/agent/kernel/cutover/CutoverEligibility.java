package io.namei.agent.kernel.cutover;

import java.util.Objects;
import java.util.Set;

public record CutoverEligibility(Set<CutoverStableCode> blockers) {
  public CutoverEligibility {
    blockers = Set.copyOf(Objects.requireNonNull(blockers, "blockers"));
  }

  public boolean eligible() {
    return blockers.isEmpty();
  }

  public static CutoverEligibility passing() {
    return new CutoverEligibility(Set.of());
  }
}
