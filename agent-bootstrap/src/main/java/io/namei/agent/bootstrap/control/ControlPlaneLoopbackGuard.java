package io.namei.agent.bootstrap.control;

import java.util.Objects;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;

final class ControlPlaneLoopbackGuard {
  ControlPlaneLoopbackGuard(ApplicationContext context, Environment environment) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(environment, "environment");
    if (!(context instanceof WebApplicationContext)) {
      throw new IllegalStateException("LOOPBACK 控制面只允许 Servlet 模式");
    }
    String address = environment.getProperty("server.address");
    if (!"127.0.0.1".equals(address) && !"::1".equals(address)) {
      throw new IllegalStateException("LOOPBACK 控制面要求 server.address 为字面 Loopback 地址");
    }
  }
}
