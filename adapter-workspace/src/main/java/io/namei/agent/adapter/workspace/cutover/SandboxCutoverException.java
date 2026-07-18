package io.namei.agent.adapter.workspace.cutover;

import io.namei.agent.kernel.cutover.CutoverStableCode;
import java.util.Objects;

public final class SandboxCutoverException extends RuntimeException {
  private final CutoverStableCode code;

  SandboxCutoverException(CutoverStableCode code, Throwable cause) {
    super("Sandbox Cutover 被拒绝: " + Objects.requireNonNull(code, "code").name(), cause);
    this.code = code;
  }

  public CutoverStableCode code() {
    return code;
  }

  static SandboxCutoverException unsafe(Throwable cause) {
    return new SandboxCutoverException(CutoverStableCode.SANDBOX_UNSAFE, cause);
  }

  static SandboxCutoverException backup(Throwable cause) {
    return new SandboxCutoverException(CutoverStableCode.BACKUP_INVALID, cause);
  }
}
