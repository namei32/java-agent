package io.namei.agent.kernel.port;

import io.namei.agent.kernel.proactive.ProactiveJobInspectionSnapshot;
import java.util.List;

/** 对本地 Active Proactive Job 的只读、Hash 安全检视。 */
public interface ProactiveJobInspectionPort {
  List<ProactiveJobInspectionSnapshot> listActive(int limit);
}
