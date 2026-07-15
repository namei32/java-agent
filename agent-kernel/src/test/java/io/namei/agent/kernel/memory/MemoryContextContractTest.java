package io.namei.agent.kernel.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.port.MemoryProfilePort;
import io.namei.agent.kernel.port.MemoryRetrievalPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryContextContractTest {
  private static final Instant NOW = Instant.parse("2026-07-14T06:00:00Z");
  private static final String SESSION_BINDING = "a".repeat(64);

  @Test
  void fixesReadOnlyModesAndEmptyPorts() {
    assertThat(MemoryRuntimeMode.values())
        .containsExactly(
            MemoryRuntimeMode.DISABLED, MemoryRuntimeMode.READ_ONLY, MemoryRuntimeMode.JAVA_NATIVE);
    assertThat(MemoryProfilePort.empty().load()).isEqualTo(MemoryProfile.empty());

    var request = request(List.of());
    assertThat(MemoryRetrievalPort.disabled().retrieve(request))
        .isEqualTo(MemoryRetrievalResult.disabled());
  }

  @Test
  void keepsProfileContentExactAndRejectsNullFields() {
    var profile = new MemoryProfile(" self \n", "# memory\n", "# recent\n");

    assertThat(profile.selfModel()).isEqualTo(" self \n");
    assertThat(profile.longTermMemory()).isEqualTo("# memory\n");
    assertThat(profile.recentContext()).isEqualTo("# recent\n");
    assertThatThrownBy(() -> new MemoryProfile(null, "", ""))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void copiesRetrievalHistoryAndAcceptsOnlyPersistedConversationRoles() {
    var history = new ArrayList<ChatMessage>();
    history.add(new ChatMessage(MessageRole.USER, "第一问"));
    var request = request(history);
    history.add(new ChatMessage(MessageRole.ASSISTANT, "后来添加"));

    assertThat(request.history()).extracting(ChatMessage::content).containsExactly("第一问");
    assertThatThrownBy(() -> request(List.of(new ChatMessage(MessageRole.SYSTEM, "系统"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryRetrievalRequest("invalid-binding", "问题", List.of(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enforcesRetrievalResultAndSafeTraceInvariants() {
    assertThat(MemoryRetrievalResult.retrieved("  记忆块  ", 2).block()).isEqualTo("记忆块");
    assertThat(MemoryRetrievalResult.retrieved("记忆块", 2).trace())
        .isEqualTo(new MemoryRetrievalTrace(MemoryRetrievalStatus.RETRIEVED, 2));
    assertThat(MemoryRetrievalResult.empty().trace().status())
        .isEqualTo(MemoryRetrievalStatus.EMPTY);
    assertThat(MemoryRetrievalResult.degraded().trace().status())
        .isEqualTo(MemoryRetrievalStatus.DEGRADED);
    assertThatThrownBy(
            () ->
                new MemoryRetrievalResult(
                    "", new MemoryRetrievalTrace(MemoryRetrievalStatus.RETRIEVED, 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MemoryRetrievalTrace(MemoryRetrievalStatus.EMPTY, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MemoryRetrievalRequest request(List<ChatMessage> history) {
    return new MemoryRetrievalRequest(SESSION_BINDING, "当前问题", history, NOW);
  }
}
