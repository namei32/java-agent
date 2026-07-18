package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.proactive.ProactiveJobRef;
import io.namei.agent.kernel.proactive.SubagentBudget;
import io.namei.agent.kernel.proactive.SubagentRequest;
import io.namei.agent.kernel.proactive.SubagentResult;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedSubagentRunnerTest {
  @Test
  void parentCancellationPreventsAnySubagentExecution() {
    var called = new AtomicBoolean();
    var source = new TurnCancellationSource();
    source.cancel();
    var runner =
        new BoundedSubagentRunner(
            request -> {
              called.set(true);
              return "unexpected";
            });

    assertThat(runner.run(request(), source.token()).status())
        .isEqualTo(SubagentResult.Status.CANCELLED);
    assertThat(called).isFalse();
  }

  @Test
  void resultOverBudgetIsNotProjected() {
    var runner = new BoundedSubagentRunner(request -> "x".repeat(9));

    var result = runner.run(request(), TurnCancellation.none());

    assertThat(result.status()).isEqualTo(SubagentResult.Status.BUDGET_EXHAUSTED);
    assertThat(result.text()).isEmpty();
  }

  private static SubagentRequest request() {
    return new SubagentRequest(
        ProactiveJobRef.parse("daily-summary"),
        "read-only diagnostic",
        new SubagentBudget(64, 8, 1, Duration.ofSeconds(1)));
  }
}
