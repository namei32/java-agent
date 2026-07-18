package io.namei.agent.kernel.proactive;

public final class ProactiveContract {
  public static final int CURRENT_VERSION = 1;

  private ProactiveContract() {}

  static ProactiveContractViolation violation(ProactiveStableCode code) {
    return new ProactiveContractViolation(code);
  }
}
