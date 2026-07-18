package io.namei.agent.bootstrap.plugin;

import io.namei.agent.kernel.plugin.PluginStableCode;
import io.namei.agent.kernel.plugin.PluginTapException;

public final class ExternalStdioPluginException extends PluginTapException {
  public ExternalStdioPluginException(PluginStableCode code) {
    super(code);
  }
}
