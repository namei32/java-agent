package io.namei.agent.kernel.control;

public final class ControlPlaneContract {
  public static final int CURRENT_VERSION = 1;
  public static final int TURN_REFERENCE_BYTES = 16;
  public static final int TURN_REFERENCE_CHARACTERS = 22;

  private ControlPlaneContract() {}

  public static void requireCurrentVersion(int schemaVersion) {
    if (schemaVersion != CURRENT_VERSION) {
      throw new IllegalArgumentException("不支持的控制面 Contract 版本");
    }
  }
}
