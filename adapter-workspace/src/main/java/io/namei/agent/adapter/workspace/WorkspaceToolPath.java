package io.namei.agent.adapter.workspace;

import java.nio.file.Path;
import java.util.ArrayList;

/** 已规范化、相对 Root，且不含导航或控制片段的 POSIX 路径。 */
public record WorkspaceToolPath(String value) {
  public WorkspaceToolPath {
    validate(value);
  }

  public static WorkspaceToolPath parse(String value) {
    return new WorkspaceToolPath(value);
  }

  private static void validate(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 4_096
        || value.startsWith("/")
        || value.startsWith("\\")) {
      throw rejected();
    }
    Path candidate;
    try {
      candidate = Path.of(value);
    } catch (RuntimeException invalid) {
      throw rejected();
    }
    if (candidate.isAbsolute()) {
      throw rejected();
    }
    String[] rawSegments = value.split("/", -1);
    var safeSegments = new ArrayList<String>(rawSegments.length);
    for (String segment : rawSegments) {
      if (segment.isEmpty()
          || segment.equals(".")
          || segment.equals("..")
          || segment.equals("~")
          || segment.indexOf('\\') >= 0
          || hasControlCharacter(segment)) {
        throw rejected();
      }
      safeSegments.add(segment);
    }
    if (!String.join("/", safeSegments).equals(value)) {
      throw rejected();
    }
  }

  public Path relativePath() {
    return Path.of(value);
  }

  private static boolean hasControlCharacter(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static WorkspaceToolContractException rejected() {
    return WorkspaceToolError.WORKSPACE_PATH_REJECTED.violation();
  }
}
