package io.namei.agent.kernel.port;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.PendingTurnAnchor;
import io.namei.agent.kernel.model.PendingTurnResolution;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class SessionRepositoryContractTest {
  @Test
  void conditionalAppendRejectsANegativeExpectedSequenceBeforeOpeningPersistence() {
    SessionRepository repository =
        new SessionRepository() {
          @Override
          public io.namei.agent.kernel.model.SessionSnapshot load(String sessionId) {
            throw new AssertionError("不应读取");
          }

          @Override
          public void appendTurn(String sessionId, io.namei.agent.kernel.model.PersistedTurn turn) {
            throw new AssertionError("不应写入");
          }
        };

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.appendTurnIfNextSequence("session", -1, null));
  }

  @Test
  void pendingResolutionDefaultFailsClosedBeforeAnyPersistenceAccess() {
    SessionRepository repository =
        new SessionRepository() {
          @Override
          public io.namei.agent.kernel.model.SessionSnapshot load(String sessionId) {
            throw new AssertionError("不应读取");
          }

          @Override
          public void appendTurn(String sessionId, io.namei.agent.kernel.model.PersistedTurn turn) {
            throw new AssertionError("不应写入");
          }
        };
    PendingTurnAnchor anchor =
        PendingTurnAnchor.pending("AAAAAAAAAAAAAAAAAAAAAA", "session", 0, "pending-projection-v1");
    PendingTurnResolution resolution =
        new PendingTurnResolution(
            "pending-projection-v1",
            new ChatMessage(MessageRole.ASSISTANT, "安全完成投影"),
            OffsetDateTime.parse("2026-07-19T09:00:00+08:00"));

    assertThatThrownBy(() -> repository.appendPendingResolutionIfAnchorMatches(anchor, resolution))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
