package io.namei.agent.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque local-only reference for one P6 approved proactive NOTE write rehearsal. */
record ProactiveMemoryNoteWriteOperationReference(String value) {
  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");

  ProactiveMemoryNoteWriteOperationReference {
    value = Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("主动 NOTE 写入操作引用格式无效");
    }
  }

  static ProactiveMemoryNoteWriteOperationReference of(String value) {
    return new ProactiveMemoryNoteWriteOperationReference(value);
  }

  @Override
  public String toString() {
    return "ProactiveMemoryNoteWriteOperationReference[value=<redacted>]";
  }
}

@FunctionalInterface
interface ProactiveMemoryNoteWriteOperationReferenceGenerator {
  ProactiveMemoryNoteWriteOperationReference next();
}
