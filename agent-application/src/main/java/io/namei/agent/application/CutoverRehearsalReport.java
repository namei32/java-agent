package io.namei.agent.application;

import io.namei.agent.kernel.cutover.CutoverBackupManifest;
import io.namei.agent.kernel.cutover.CutoverDifferenceReport;
import io.namei.agent.kernel.cutover.CutoverEligibility;
import io.namei.agent.kernel.cutover.CutoverPlan;
import java.util.Objects;
import java.util.Optional;

/** 安全报告：仅包含状态、代码和 Hash；不投影文件系统 Root 或内容。 */
public record CutoverRehearsalReport(
    CutoverPlan plan,
    CutoverEligibility eligibility,
    Optional<CutoverBackupManifest> manifest,
    Optional<CutoverDifferenceReport> difference) {
  public CutoverRehearsalReport {
    plan = Objects.requireNonNull(plan, "plan");
    eligibility = Objects.requireNonNull(eligibility, "eligibility");
    manifest = Objects.requireNonNull(manifest, "manifest");
    difference = Objects.requireNonNull(difference, "difference");
  }
}
