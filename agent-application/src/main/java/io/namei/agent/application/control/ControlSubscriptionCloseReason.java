package io.namei.agent.application.control;

public enum ControlSubscriptionCloseReason {
  TERMINAL,
  SLOW_CONSUMER,
  CLIENT_DISCONNECTED,
  SESSION_REVOKED,
  LIFETIME_EXCEEDED,
  SOURCE_ENDED,
  SHUTDOWN
}
