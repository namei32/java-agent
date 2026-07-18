package io.namei.agent.application;

import io.namei.agent.kernel.cutover.CutoverEligibility;
import io.namei.agent.kernel.cutover.CutoverMode;
import io.namei.agent.kernel.cutover.CutoverPlan;
import io.namei.agent.kernel.cutover.CutoverStableCode;
import io.namei.agent.kernel.port.CutoverSandboxPort;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Moves only an offline rehearsal to READY. No method can enter a production cutover state. */
public final class CutoverRehearsalService {
  private final CutoverSandboxPort sandbox;

  public CutoverRehearsalService(CutoverSandboxPort sandbox) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
  }

  public CutoverRehearsalReport rehearse(
      CutoverMode mode, String sandboxHash, boolean verifiedOfflineEvidence) {
    CutoverPlan plan =
        new CutoverPlan(mode, sandboxHash, io.namei.agent.kernel.cutover.CutoverState.DRAFT);
    CutoverEligibility eligibility =
        verifiedOfflineEvidence
            ? CutoverEligibility.passing()
            : new CutoverEligibility(Set.of(CutoverStableCode.PRECONDITION_MISSING));
    plan = plan.withEligibility(eligibility);
    if (!eligibility.eligible() || mode == CutoverMode.PLAN_ONLY) {
      return new CutoverRehearsalReport(plan, eligibility, Optional.empty(), Optional.empty());
    }
    var manifest = sandbox.backup();
    plan = plan.markBackedUp();
    if (!sandbox.verify(manifest)) {
      throw new IllegalStateException(CutoverStableCode.BACKUP_INVALID.name());
    }
    var difference = sandbox.compare(manifest, 0);
    difference.requireWithinThreshold();
    plan = plan.markRehearsed().markReady();
    return new CutoverRehearsalReport(
        plan, eligibility, Optional.of(manifest), Optional.of(difference));
  }
}
