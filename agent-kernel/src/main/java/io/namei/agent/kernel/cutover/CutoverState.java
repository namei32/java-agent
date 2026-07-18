package io.namei.agent.kernel.cutover;

public enum CutoverState {
  DRAFT,
  ELIGIBLE,
  BACKED_UP,
  REHEARSED,
  READY,
  CUTTING_OVER,
  OBSERVING,
  ROLLED_BACK,
  COMPLETED
}
