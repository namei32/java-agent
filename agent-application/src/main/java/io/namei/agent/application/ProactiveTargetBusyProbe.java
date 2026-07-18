package io.namei.agent.application;

@FunctionalInterface
public interface ProactiveTargetBusyProbe {
  boolean isBusy(String targetHash);

  static ProactiveTargetBusyProbe none() {
    return ignored -> false;
  }
}
