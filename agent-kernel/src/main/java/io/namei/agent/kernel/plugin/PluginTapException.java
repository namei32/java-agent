package io.namei.agent.kernel.plugin;

import java.util.Objects;

/** 观察型 Tap 可安全上报给调度器的稳定失败类别。 */
public class PluginTapException extends Exception {
  private final PluginStableCode code;

  public PluginTapException(PluginStableCode code) {
    super(Objects.requireNonNull(code, "code").name());
    this.code = code;
  }

  public PluginStableCode code() {
    return code;
  }
}
