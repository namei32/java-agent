package io.namei.agent.application;

import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import java.util.Objects;
import java.util.regex.Pattern;

/** Derives the P6-only opaque scope; it deliberately accepts no Chat Session. */
final class ProactiveMemoryNoteWriteScope {
  private static final Pattern TARGET_HASH = Pattern.compile("[0-9a-f]{64}");

  private ProactiveMemoryNoteWriteScope() {}

  static MemoryScope derive(ProactiveJobRef jobRef, String targetHash) {
    Objects.requireNonNull(jobRef, "jobRef");
    if (targetHash == null || !TARGET_HASH.matcher(targetHash).matches()) {
      throw new IllegalArgumentException("主动 NOTE 写入 Target Hash 无效");
    }
    String preimage = "r14-p6-note:" + jobRef.value() + ":" + targetHash;
    return new MemoryScope(ApprovalFingerprint.sessionBinding(preimage));
  }
}
