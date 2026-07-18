package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class ControlPlaneLoopbackBindingTest {
  @Test
  void acceptsOnlyLiteralIpv4AndIpv6LoopbackBindings() {
    for (String address : new String[] {"127.0.0.1", "::1"}) {
      webRunner(address)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ControlPlaneRuntime.class);
              });
    }
  }

  @Test
  void rejectsWildcardRemoteHostnameAndUnprovableBindingsBeforeRuntimeCreation() {
    for (String address :
        new String[] {"0.0.0.0", "::", "192.168.1.10", "localhost", "example.test", ""}) {
      webRunner(address)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("controlPlaneLoopbackGuard")
                    .hasStackTraceContaining("server.address 为字面 Loopback 地址");
              });
    }
  }

  @Test
  void rejectsLoopbackModeInNonWebCliContext() {
    new ApplicationContextRunner()
        .withUserConfiguration(ControlPlaneConfiguration.class)
        .withPropertyValues("agent.control-plane.mode=LOOPBACK", "server.address=127.0.0.1")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("controlPlaneLoopbackGuard")
                  .hasStackTraceContaining("LOOPBACK 控制面只允许 Servlet 模式");
            });
  }

  private static WebApplicationContextRunner webRunner(String address) {
    return new WebApplicationContextRunner()
        .withUserConfiguration(ControlPlaneConfiguration.class)
        .withPropertyValues("agent.control-plane.mode=LOOPBACK", "server.address=" + address);
  }
}
