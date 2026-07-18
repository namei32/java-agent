package io.namei.agent.kernel.cutover;

public enum CutoverMode {
  PLAN_ONLY,
  REHEARSAL;

  public static CutoverMode parse(String value) {
    try {
      CutoverMode parsed = valueOf(value);
      if (!parsed.name().equals(value)) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw CutoverContract.violation(CutoverStableCode.CUTOVER_CONTRACT_INVALID);
    }
  }
}
