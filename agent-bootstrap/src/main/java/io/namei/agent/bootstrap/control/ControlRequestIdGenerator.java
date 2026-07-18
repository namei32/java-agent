package io.namei.agent.bootstrap.control;

import java.util.UUID;

@FunctionalInterface
public interface ControlRequestIdGenerator {
  String next();

  static ControlRequestIdGenerator secure() {
    return () -> UUID.randomUUID().toString();
  }
}
