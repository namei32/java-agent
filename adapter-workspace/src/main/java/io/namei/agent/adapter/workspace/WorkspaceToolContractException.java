package io.namei.agent.adapter.workspace;

import java.util.Objects;

/** 消息中绝不包含 Workspace 或操作系统详情的契约违规异常。 */
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
