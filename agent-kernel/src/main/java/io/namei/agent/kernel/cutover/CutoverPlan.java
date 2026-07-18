package io.namei.agent.kernel.cutover;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable state machine. Production states are representable for reporting but never enterable
 * here.
 */
public record CutoverPlan(CutoverMode mode, String sandboxHash, CutoverState state) {
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public CutoverPlan {
    mode = Objects.requireNonNull(mode, "mode");
    state = Objects.requireNonNull(state, "state");
    if (sandboxHash == null
        || !HASH.matcher(sandboxHash).matches()
        || state == CutoverState.CUTTING_OVER
        || state == CutoverState.OBSERVING
        || state == CutoverState.COMPLETED) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_CONTRACT_INVALID);
    }
  }

  public CutoverPlan withEligibility(CutoverEligibility eligibility) {
    Objects.requireNonNull(eligibility, "eligibility");
    require(CutoverState.DRAFT);
    if (!eligibility.eligible()) {
      return this;
    }
    return new CutoverPlan(mode, sandboxHash, CutoverState.ELIGIBLE);
  }

  public CutoverPlan markBackedUp() {
    require(CutoverState.ELIGIBLE);
    return new CutoverPlan(mode, sandboxHash, CutoverState.BACKED_UP);
  }

  public CutoverPlan markRehearsed() {
    require(CutoverState.BACKED_UP);
    return new CutoverPlan(mode, sandboxHash, CutoverState.REHEARSED);
  }

  public CutoverPlan markReady() {
    require(CutoverState.REHEARSED);
    return new CutoverPlan(mode, sandboxHash, CutoverState.READY);
  }

  public CutoverPlan markCuttingOver() {
    if (state != CutoverState.READY) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_STATE_INVALID);
    }
    throw CutoverContract.violation(CutoverStableCode.CUTOVER_PRODUCTION_FORBIDDEN);
  }

  private void require(CutoverState expected) {
    if (state != expected) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_STATE_INVALID);
    }
  }
}
