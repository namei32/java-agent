package io.namei.agent.bootstrap.control;

public final class OperatorSessionCapacityException extends RuntimeException {
  public OperatorSessionCapacityException() {
    super("控制面 Session 容量已满", null, false, false);
  }
}
