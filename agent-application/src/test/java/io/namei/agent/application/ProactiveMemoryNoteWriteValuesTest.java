package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.memory.MemoryScope;
import io.namei.agent.kernel.memory.MemorySourceKind;
import io.namei.agent.kernel.memory.MemoryType;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import org.junit.jupiter.api.Test;

class ProactiveMemoryNoteWriteValuesTest {
  private static final String TARGET_HASH = "a".repeat(64);
  private static final ProactiveJobRef JOB_REF = ProactiveJobRef.parse("daily-summary");
  private static final ProactiveMemoryNoteWriteOperationReference REFERENCE =
      ProactiveMemoryNoteWriteOperationReference.of("AAAAAAAAAAAAAAAAAAAAAA");

  @Test
  void fixesTheDedicatedCapabilityAndApprovedMemoryProvenance() {
    assertThat(ProactiveMemoryNoteWriteCapability.CAPABILITY_NAME)
        .isEqualTo("proactive_memory_note_write");
    assertThat(ProactiveMemoryNoteWriteCapability.CAPABILITY_VERSION)
        .isEqualTo("r14-proactive-memory-note-v1");
    assertThat(ProactiveMemoryNoteWriteCapability.FIXED_EMBEDDING_MODEL)
        .isEqualTo("fake-r14-p6-memory-v1");
    assertThat(ProactiveMemoryNoteWriteCapability.memoryType()).isEqualTo(MemoryType.NOTE);
    assertThat(ProactiveMemoryNoteWriteCapability.emotionalWeight()).isZero();
    assertThat(ProactiveMemoryNoteWriteCapability.sourceKind())
        .isEqualTo(MemorySourceKind.PROACTIVE_APPROVED);
  }

  @Test
  void derivesScopeAndRequestIdWithoutAcceptingAChatSession() {
    MemoryScope scope = ProactiveMemoryNoteWriteScope.derive(JOB_REF, TARGET_HASH);

    assertThat(scope.binding())
        .isEqualTo(
            new MemoryScope(
                    ApprovalFingerprint.sessionBinding("r14-p6-note:daily-summary:" + TARGET_HASH))
                .binding());
    assertThat(scope.toString()).isEqualTo("MemoryScope[redacted]");
    assertThat(ProactiveMemoryNoteWriteCapability.requestIdFor(REFERENCE))
        .isEqualTo("p6-note-AAAAAAAAAAAAAAAAAAAAAA");
  }

  @Test
  void redactsOperationReferenceAndRejectsInvalidTargetHash() {
    assertThat(REFERENCE.toString()).doesNotContain("AAAAAAAAAAAAAAAAAAAAAA");
    assertThatThrownBy(() -> ProactiveMemoryNoteWriteScope.derive(JOB_REF, "not-a-hash"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
