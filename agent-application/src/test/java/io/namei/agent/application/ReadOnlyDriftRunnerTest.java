package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.proactive.DriftRequest;
import io.namei.agent.kernel.proactive.DriftResult;
import io.namei.agent.kernel.proactive.ProactiveJobRef;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ReadOnlyDriftRunnerTest {
  @Test
  void cancellationPreventsProbeAndReturnsNoDiagnosticText() {
    var called = new AtomicBoolean();
    var source = new TurnCancellationSource();
    source.cancel();
    var runner =
        new ReadOnlyDriftRunner(
            request -> {
              called.set(true);
              return DriftResult.clean();
            });

    var result = runner.inspect(request(), source.token());

    assertThat(result.status()).isEqualTo(DriftResult.Status.CANCELLED);
    assertThat(result.safeSummary()).isEmpty();
    assertThat(called).isFalse();
  }

  private static DriftRequest request() {
    return new DriftRequest(ProactiveJobRef.parse("daily-summary"), "a".repeat(64), 512);
  }
}
