package io.namei.agent.bootstrap.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginKind;
import io.namei.agent.kernel.plugin.PluginManifest;
import io.namei.agent.kernel.plugin.PluginStableCode;
import io.namei.agent.kernel.plugin.PluginTapEvent;
import io.namei.agent.kernel.plugin.PluginTapOutcome;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ExternalStdioPluginBridgeTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final PluginManifest MANIFEST =
      new PluginManifest(
          1,
          PluginId.parse("external-observer"),
          "1.0.0",
          1,
          PluginKind.EXTERNAL_STDIO,
          List.of(PluginCapability.TURN_TAP));

  @Test
  void performsHelloThenSendsOnlySafeTapProjectionWithMatchingRequestId() throws Exception {
    var transport =
        new ScriptedTransport(
            response("hello-1", Map.of("manifest", manifestMap())),
            response("tap-2", Map.of("accepted", true)));
    var bridge =
        ExternalStdioPluginBridge.start(
            transport, MANIFEST, limits(), new ScriptedRequestIds("hello-1", "tap-2"));

    bridge.accept(
        new PluginTapEvent(
            PluginCapability.TURN_TAP,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            PluginTapOutcome.COMPLETED,
            null,
            12));

    assertThat(transport.requests).hasSize(2);
    JsonNode hello = JSON.readTree(transport.requests.getFirst());
    JsonNode tap = JSON.readTree(transport.requests.getLast());
    assertThat(hello.path("method").asString()).isEqualTo("hello");
    assertThat(tap.path("requestId").asString()).isEqualTo("tap-2");
    assertThat(tap.path("method").asString()).isEqualTo("turn.tap");
    assertThat(tap.path("params").has("referenceHash")).isTrue();
    assertThat(tap.path("params").has("prompt")).isFalse();
    assertThat(tap.path("params").has("sessionId")).isFalse();
  }

  @Test
  void rejectsWrongCorrelationOrManifestBeforeItCanBecomeActive() {
    var wrongCorrelation =
        new ScriptedTransport(response("other", Map.of("manifest", manifestMap())));
    var wrongManifest =
        new ScriptedTransport(
            response("hello-1", Map.of("manifest", Map.of("id", "other", "schemaVersion", 1))));

    assertCode(
        () ->
            ExternalStdioPluginBridge.start(
                wrongCorrelation, MANIFEST, limits(), new ScriptedRequestIds("hello-1")),
        PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    assertCode(
        () ->
            ExternalStdioPluginBridge.start(
                wrongManifest, MANIFEST, limits(), new ScriptedRequestIds("hello-1")),
        PluginStableCode.PLUGIN_MANIFEST_INVALID);
  }

  @Test
  void boundsFramesAndMapsTransportFailureWithoutLeakingDetails() {
    var oversized = new ScriptedTransport("x".repeat(257));
    var exited = new ScriptedTransport(new IOException("child secret output"));

    assertCode(
        () ->
            ExternalStdioPluginBridge.start(
                oversized, MANIFEST, limits(), new ScriptedRequestIds("hello-1")),
        PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    assertCode(
        () ->
            ExternalStdioPluginBridge.start(
                exited, MANIFEST, limits(), new ScriptedRequestIds("hello-1")),
        PluginStableCode.PLUGIN_PROCESS_EXITED);
  }

  @Test
  void closesWithOneSharedDeadlineAndRejectsTapsAfterClosing() throws Exception {
    var transport =
        new ScriptedTransport(
            response("hello-1", Map.of("manifest", manifestMap())),
            response("shutdown-2", Map.of("accepted", true)));
    var bridge =
        ExternalStdioPluginBridge.start(
            transport, MANIFEST, limits(), new ScriptedRequestIds("hello-1", "shutdown-2"));

    bridge.close();

    assertThat(transport.closedWith).containsExactly(Duration.ofMillis(100));
    assertCode(
        () ->
            bridge.accept(
                new PluginTapEvent(
                    PluginCapability.TURN_TAP,
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    PluginTapOutcome.COMPLETED,
                    null,
                    1)),
        PluginStableCode.PLUGIN_SHUTTING_DOWN);
  }

  @Test
  void protocolFailureDuringTapClosesOnlyThatBridgeAndDoesNotAllowReplay() throws Exception {
    var transport =
        new ScriptedTransport(response("hello-1", Map.of("manifest", manifestMap())), "not-json");
    var bridge =
        ExternalStdioPluginBridge.start(
            transport, MANIFEST, limits(), new ScriptedRequestIds("hello-1", "tap-2"));

    assertCode(
        () ->
            bridge.accept(
                new PluginTapEvent(
                    PluginCapability.TURN_TAP,
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    PluginTapOutcome.COMPLETED,
                    null,
                    1)),
        PluginStableCode.PLUGIN_PROTOCOL_INVALID);

    assertThat(transport.closedWith).containsExactly(Duration.ofMillis(100));
    assertCode(
        () ->
            bridge.accept(
                new PluginTapEvent(
                    PluginCapability.TURN_TAP,
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    PluginTapOutcome.COMPLETED,
                    null,
                    1)),
        PluginStableCode.PLUGIN_SHUTTING_DOWN);
  }

  private static ExternalStdioBridgeLimits limits() {
    return new ExternalStdioBridgeLimits(256, Duration.ofMillis(80), Duration.ofMillis(100));
  }

  private static Map<String, Object> manifestMap() {
    return Map.of(
        "schemaVersion",
        1,
        "id",
        "external-observer",
        "version",
        "1.0.0",
        "apiVersion",
        1,
        "kind",
        "EXTERNAL_STDIO",
        "capabilities",
        List.of("TURN_TAP"));
  }

  private static String response(String requestId, Map<String, Object> result) {
    return JSON.writeValueAsString(Map.of("requestId", requestId, "ok", true, "result", result));
  }

  private static void assertCode(ThrowingOperation operation, PluginStableCode code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(ExternalStdioPluginException.class)
        .satisfies(
            error -> assertThat(((ExternalStdioPluginException) error).code()).isEqualTo(code));
  }

  @FunctionalInterface
  private interface ThrowingOperation {
    void run() throws Exception;
  }

  private static final class ScriptedRequestIds implements PluginRequestIdGenerator {
    private final ArrayDeque<String> ids;

    private ScriptedRequestIds(String... ids) {
      this.ids = new ArrayDeque<>(List.of(ids));
    }

    @Override
    public String next() {
      return ids.removeFirst();
    }
  }

  private static final class ScriptedTransport implements ExternalStdioPluginTransport {
    private final ArrayDeque<Object> responses;
    private final List<String> requests = new java.util.ArrayList<>();
    private final List<Duration> closedWith = new java.util.ArrayList<>();

    private ScriptedTransport(Object... responses) {
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public String exchange(String request, Duration timeout) throws IOException {
      requests.add(request);
      Object response = responses.removeFirst();
      if (response instanceof IOException failure) {
        throw failure;
      }
      return (String) response;
    }

    @Override
    public void close(Duration timeout) {
      closedWith.add(timeout);
    }
  }
}
