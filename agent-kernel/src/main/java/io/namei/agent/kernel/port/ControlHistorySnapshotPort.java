package io.namei.agent.kernel.port;

import io.namei.agent.kernel.control.HistoryDetailPage;
import io.namei.agent.kernel.control.HistoryDetailReadRequest;
import io.namei.agent.kernel.control.HistoryScopeCapability;
import io.namei.agent.kernel.control.HistorySnapshotUnavailableException;
import java.util.Objects;

/** Read-only zero-content history projection for one already-authorized Scope Capability. */
@FunctionalInterface
public interface ControlHistorySnapshotPort {
  HistoryDetailPage read(HistoryScopeCapability scope, HistoryDetailReadRequest request);

  static ControlHistorySnapshotPort disabled() {
    return (scope, request) -> {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(request, "request");
      throw new HistorySnapshotUnavailableException();
    };
  }
}
