package io.namei.agent.application;

import io.namei.agent.kernel.cutover.CutoverEligibility;
import io.namei.agent.kernel.cutover.CutoverMode;
import io.namei.agent.kernel.cutover.CutoverPlan;
import io.namei.agent.kernel.cutover.CutoverStableCode;
import io.namei.agent.kernel.port.CutoverSandboxPort;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 仅将离线演练推进到 READY；任何方法都不能进入生产切换状态。 */
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
