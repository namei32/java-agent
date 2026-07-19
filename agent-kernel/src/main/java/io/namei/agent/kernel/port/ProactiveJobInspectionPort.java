package io.namei.agent.kernel.port;

import io.namei.agent.kernel.proactive.ProactiveJobInspectionSnapshot;
import java.util.List;

/** Read-only, hash-safe inspection of active local proactive jobs. */
public interface ProactiveJobInspectionPort {
  List<ProactiveJobInspectionSnapshot> listActive(int limit);
}
