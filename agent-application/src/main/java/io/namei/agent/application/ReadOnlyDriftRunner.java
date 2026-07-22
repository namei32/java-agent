package io.namei.agent.application;

import io.namei.agent.kernel.proactive.DriftRequest;
import io.namei.agent.kernel.proactive.DriftResult;
import java.util.Objects;

/** 在只读诊断 Probe 外围强制实施取消与安全投影。 */
public final class ReadOnlyDriftRunner {
  private final ReadOnlyDriftProbe probe;

  public ReadOnlyDriftRunner(ReadOnlyDriftProbe probe) {
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  public DriftResult inspect(DriftRequest request, TurnCancellation parentCancellation) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(parentCancellation, "parentCancellation");
    if (parentCancellation.isCancellationRequested()) {
      return DriftResult.cancelled();
    }
    DriftResult result = probe.inspect(request);
    if (parentCancellation.isCancellationRequested()) {
      return DriftResult.cancelled();
    }
    if (result == null) {
      return DriftResult.clean();
    }
    if (result.safeSummary().isPresent()
        && result
                .safeSummary()
                .orElseThrow()
                .codePointCount(0, result.safeSummary().orElseThrow().length())
            > request.maxSummaryCharacters()) {
      return DriftResult.clean();
    }
    return result;
  }
}
