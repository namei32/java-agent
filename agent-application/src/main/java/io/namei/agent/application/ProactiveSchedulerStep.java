package io.namei.agent.application;

public enum ProactiveSchedulerStep {
  IDLE,
  COMPLETED,
  CANCELLED,
  LEASE_LOST,
  FAILED,
  CLOSED
}
