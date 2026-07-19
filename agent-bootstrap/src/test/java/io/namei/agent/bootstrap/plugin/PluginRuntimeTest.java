package io.namei.agent.bootstrap.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.kernel.plugin.AgentPlugin;
import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginKind;
import io.namei.agent.kernel.plugin.PluginLifecyclePhase;
import io.namei.agent.kernel.plugin.PluginManifest;
import io.namei.agent.kernel.plugin.PluginTap;
import io.namei.agent.kernel.plugin.PluginTapOutcome;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PluginRuntimeTest {
  @Test
  void disabledRuntimeDoesNotDiscoverStartProcessesOrAllocateAnActiveDispatcher() {
    var loads = new AtomicInteger();
    var properties = new PluginProperties("DISABLED", null, null, null, null, null);
    var discovery = new JavaServicePluginDiscovery(() -> failIfLoaded(loads));

    try (var runtime =
        PluginRuntime.start(
            properties,
            ToolRuntimeMode.READ_ONLY,
            discovery,
            (command, limits) -> {
              throw new AssertionError("DISABLED 不得启动外部进程");
            })) {
      assertThat(runtime.active()).isFalse();
      assertThatCode(
              () ->
                  runtime
                      .lifecycleObserver()
                      .onEvent(io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.turnStarted()))
          .doesNotThrowAnyException();
    }

    assertThat(loads).hasValue(0);
  }

  @Test
  void rejectsAnyEnabledPluginModeUnlessGlobalToolsAreReadOnly() {
    var properties =
        new PluginProperties("JAVA_SERVICE", null, null, null, List.of("observer"), List.of());

    assertThatThrownBy(
            () ->
                PluginRuntime.start(
                    properties,
                    ToolRuntimeMode.APPROVAL_REQUIRED,
                    new JavaServicePluginDiscovery(List::of),
                    (command, limits) -> {
                      throw new AssertionError();
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ_ONLY");
  }

  @Test
  void activeJavaRuntimeProjectsChatAndToolLifecycleEventsOffTheCallerPath() throws Exception {
    var events = new CopyOnWriteArrayList<io.namei.agent.kernel.plugin.PluginTapEvent>();
    var received = new LinkedBlockingQueue<io.namei.agent.kernel.plugin.PluginTapEvent>();
    var plugin =
        new AgentPlugin() {
          @Override
          public PluginManifest manifest() {
            return new PluginManifest(
                1,
                PluginId.parse("observer"),
                "1.0.0",
                1,
                PluginKind.JAVA_SERVICE,
                List.of(PluginCapability.TURN_TAP, PluginCapability.TOOL_TAP));
          }

          @Override
          public PluginTap tap() {
            return event -> {
              events.add(event);
              received.offer(event);
            };
          }
        };
    var properties =
        new PluginProperties("JAVA_SERVICE", null, null, null, List.of("observer"), List.of());

    try (var runtime =
        PluginRuntime.start(
            properties,
            ToolRuntimeMode.READ_ONLY,
            new JavaServicePluginDiscovery(() -> List.of(plugin)),
            (command, limits) -> {
              throw new AssertionError();
            })) {
      publishAndAwait(
          runtime, io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.turnStarted(), received);
      publishAndAwait(
          runtime,
          io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.toolStarted(1, "call-1", "clock"),
          received);
    }

    assertThat(events)
        .extracting(event -> event.capability())
        .containsExactlyInAnyOrder(PluginCapability.TURN_TAP, PluginCapability.TOOL_TAP);
    assertThat(events).allSatisfy(event -> assertThat(event.referenceHash()).hasSize(64));
  }

  @Test
  void apiV2LifecycleTapReceivesOnlyTheExplicitReadOnlyPhaseMapping() throws Exception {
    var events = new CopyOnWriteArrayList<io.namei.agent.kernel.plugin.PluginTapEvent>();
    var received = new LinkedBlockingQueue<io.namei.agent.kernel.plugin.PluginTapEvent>();
    var plugin =
        new AgentPlugin() {
          @Override
          public PluginManifest manifest() {
            return new PluginManifest(
                1,
                PluginId.parse("lifecycle-observer"),
                "2.0.0",
                2,
                PluginKind.JAVA_SERVICE,
                List.of(PluginCapability.LIFECYCLE_TAP));
          }

          @Override
          public PluginTap tap() {
            return event -> {
              events.add(event);
              received.offer(event);
            };
          }
        };
    var properties =
        new PluginProperties(
            "JAVA_SERVICE", null, null, null, List.of("lifecycle-observer"), List.of());

    try (var runtime =
        PluginRuntime.start(
            properties,
            ToolRuntimeMode.READ_ONLY,
            new JavaServicePluginDiscovery(() -> List.of(plugin)),
            (command, limits) -> {
              throw new AssertionError();
            })) {
      publishAndAwait(
          runtime, io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.turnStarted(), received);
      publishAndAwait(
          runtime, io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.modelRequested(1), received);
      publishAndAwait(
          runtime,
          io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.modelCompleted(1, "FINAL"),
          received);
      publishAndAwait(
          runtime,
          io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.toolStarted(1, "call-1", "read_file"),
          received);
      publishAndAwait(
          runtime,
          io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.toolCompleted(
              1, "call-1", "read_file", io.namei.agent.kernel.tool.ToolResultStatus.SUCCESS),
          received);
      publishAndAwait(
          runtime, io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.turnCommitted(), received);
      publishAndAwait(
          runtime,
          io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.turnFailed("MODEL_TIMEOUT"),
          received);
      runtime
          .lifecycleObserver()
          .onEvent(
              io.namei.agent.kernel.lifecycle.TurnLifecycleEvent.approvalRequested(
                  1, "call-2", "write_file"));
      assertThat(received.poll()).isNull();
    }

    assertThat(events)
        .extracting(event -> event.phase())
        .containsExactlyInAnyOrder(
            PluginLifecyclePhase.BEFORE_TURN,
            PluginLifecyclePhase.BEFORE_REASONING,
            PluginLifecyclePhase.AFTER_REASONING,
            PluginLifecyclePhase.BEFORE_TOOL_CALL,
            PluginLifecyclePhase.AFTER_TOOL_RESULT,
            PluginLifecyclePhase.AFTER_TURN,
            PluginLifecyclePhase.AFTER_TURN);
    assertThat(events)
        .filteredOn(event -> event.phase() == PluginLifecyclePhase.AFTER_TURN)
        .extracting(event -> event.outcome())
        .containsExactlyInAnyOrder(PluginTapOutcome.COMPLETED, PluginTapOutcome.FAILED);
    assertThat(events)
        .allSatisfy(
            event -> {
              assertThat(event.capability()).isEqualTo(PluginCapability.LIFECYCLE_TAP);
              assertThat(event.referenceHash()).matches("[0-9a-f]{64}");
            });
  }

  private static void publishAndAwait(
      PluginRuntime runtime,
      io.namei.agent.kernel.lifecycle.TurnLifecycleEvent event,
      LinkedBlockingQueue<io.namei.agent.kernel.plugin.PluginTapEvent> received)
      throws InterruptedException {
    runtime.lifecycleObserver().onEvent(event);
    assertThat(received.poll(1, TimeUnit.SECONDS)).isNotNull();
  }

  private static List<io.namei.agent.kernel.plugin.AgentPlugin> failIfLoaded(AtomicInteger loads) {
    loads.incrementAndGet();
    throw new AssertionError("不应加载 Provider");
  }
}
