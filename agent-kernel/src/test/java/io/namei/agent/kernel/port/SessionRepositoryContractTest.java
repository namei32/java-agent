package io.namei.agent.kernel.port;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
}
