package io.namei.agent.application.control;

import io.namei.agent.kernel.control.ControlPlaneContract;
import io.namei.agent.kernel.control.ControlTurnRef;
import java.security.SecureRandom;
import java.util.Objects;

@FunctionalInterface
public interface ControlTurnRefGenerator {
  ControlTurnRef next();

  static ControlTurnRefGenerator secure() {
    SecureRandom random = new SecureRandom();
    return () -> {
      byte[] bytes = new byte[ControlPlaneContract.TURN_REFERENCE_BYTES];
      random.nextBytes(bytes);
      return ControlTurnRef.fromBytes(bytes);
    };
  }

  static ControlTurnRefGenerator using(SecureRandom random) {
    Objects.requireNonNull(random, "random");
    return () -> {
      byte[] bytes = new byte[ControlPlaneContract.TURN_REFERENCE_BYTES];
      random.nextBytes(bytes);
      return ControlTurnRef.fromBytes(bytes);
    };
  }
}
