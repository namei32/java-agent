package io.namei.agent.adapter.workspace;

import java.util.Objects;

/** A contract violation whose message never contains workspace or operating-system details. */
public final class WorkspaceToolContractException extends IllegalArgumentException {
  private final WorkspaceToolError code;

  WorkspaceToolContractException(WorkspaceToolError code) {
    super(Objects.requireNonNull(code, "code").name());
    this.code = code;
  }

  public WorkspaceToolError code() {
    return code;
  }
}
