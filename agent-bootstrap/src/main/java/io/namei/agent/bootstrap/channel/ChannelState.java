package io.namei.agent.bootstrap.channel;

public enum ChannelState {
  NEW,
  STARTING,
  RUNNING,
  DEGRADED,
  FAILED,
  STOPPING,
  STOPPED
}
