package io.namei.agent.kernel.cutover;

public record CutoverDifferenceReport(int checkedEntries, int changedEntries, int threshold) {
  public CutoverDifferenceReport {
    if (checkedEntries < 0
        || changedEntries < 0
        || changedEntries > checkedEntries
        || threshold < 0) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_CONTRACT_INVALID);
    }
  }

  public boolean withinThreshold() {
    return changedEntries <= threshold;
  }

  public void requireWithinThreshold() {
    if (!withinThreshold()) {
      throw CutoverContract.violation(CutoverStableCode.DIFFERENCE_THRESHOLD_EXCEEDED);
    }
  }
}
