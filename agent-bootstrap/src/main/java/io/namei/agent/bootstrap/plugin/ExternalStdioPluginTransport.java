package io.namei.agent.bootstrap.plugin;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** 已启动 stdio 子进程的单请求线协议边界。 */
public interface ExternalStdioPluginTransport {
  String exchange(String request, Duration timeout) throws IOException, TimeoutException;

  void close(Duration timeout);
}
