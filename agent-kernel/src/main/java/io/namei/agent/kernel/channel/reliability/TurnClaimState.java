package io.namei.agent.kernel.channel.reliability;

public enum TurnClaimState {
  RESERVED,
  START_RETRYABLE,
  RUNNING,
  TERMINAL_RECORDED,
  EXECUTION_UNKNOWN
}
