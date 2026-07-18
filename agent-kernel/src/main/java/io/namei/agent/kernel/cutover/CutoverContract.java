package io.namei.agent.kernel.cutover;

final class CutoverContract {
  private CutoverContract() {}

  static CutoverContractViolation violation(CutoverStableCode code) {
    return new CutoverContractViolation(code);
  }
}
