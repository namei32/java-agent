package io.namei.agent.bootstrap.control;

public enum ControlPlaneMode {
  DISABLED,
  LOOPBACK;

  static ControlPlaneMode parse(String value) {
    if (value == null) {
      throw new NullPointerException("agent.control-plane.mode");
    }
    return switch (value) {
      case "DISABLED" -> DISABLED;
      case "LOOPBACK" -> LOOPBACK;
      default -> throw new IllegalArgumentException("agent.control-plane.mode 无效");
    };
  }
}
