package io.namei.agent.bootstrap.plugin;

import io.namei.agent.kernel.plugin.PluginCapability;
import io.namei.agent.kernel.plugin.PluginId;
import io.namei.agent.kernel.plugin.PluginKind;
import io.namei.agent.kernel.plugin.PluginManifest;
import io.namei.agent.kernel.plugin.PluginStableCode;
import io.namei.agent.kernel.plugin.PluginTap;
import io.namei.agent.kernel.plugin.PluginTapEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 受限的外部 Plugin stdio 协议。该类不创建进程；进程边界必须提供已限制的 Transport。 */
public final class ExternalStdioPluginBridge implements PluginTap, AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern REQUEST_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  private final ExternalStdioPluginTransport transport;
  private final PluginManifest manifest;
  private final ExternalStdioBridgeLimits limits;
  private final PluginRequestIdGenerator requestIds;
  private final AtomicBoolean accepting = new AtomicBoolean(true);

  private ExternalStdioPluginBridge(
      ExternalStdioPluginTransport transport,
      PluginManifest manifest,
      ExternalStdioBridgeLimits limits,
      PluginRequestIdGenerator requestIds) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
  }

  public static ExternalStdioPluginBridge start(
      ExternalStdioPluginTransport transport,
      PluginManifest expectedManifest,
      ExternalStdioBridgeLimits limits,
      PluginRequestIdGenerator requestIds)
      throws ExternalStdioPluginException {
    if (expectedManifest == null || expectedManifest.kind() != PluginKind.EXTERNAL_STDIO) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
    var bridge = new ExternalStdioPluginBridge(transport, expectedManifest, limits, requestIds);
    try {
      JsonNode result = bridge.request("hello", Map.of());
      if (!bridge.manifest.equals(parseManifest(result.get("manifest")))) {
        throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_MANIFEST_INVALID);
      }
      return bridge;
    } catch (ExternalStdioPluginException failure) {
      bridge.accepting.set(false);
      bridge.transport.close(bridge.limits.shutdownTimeout());
      throw failure;
    }
  }

  @Override
  public void accept(PluginTapEvent event) throws ExternalStdioPluginException {
    Objects.requireNonNull(event, "event");
    if (!accepting.get()) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_SHUTTING_DOWN);
    }
    if (!manifest.capabilities().contains(event.capability())) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_CAPABILITY_UNAVAILABLE);
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("referenceHash", event.referenceHash());
    params.put("outcome", event.outcome().name());
    params.put("durationMillis", event.durationMillis());
    if (event.capability() == PluginCapability.LIFECYCLE_TAP) {
      params.put("phase", event.phase().name());
    }
    if (event.code() != null) {
      params.put("code", event.code().name());
    }
    try {
      JsonNode result = request(method(event.capability()), params);
      JsonNode accepted = result.get("accepted");
      if (accepted == null || !accepted.isBoolean() || !accepted.asBoolean()) {
        fail(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
      }
    } catch (ExternalStdioPluginException failure) {
      disableAfterFailure();
      throw failure;
    }
  }

  @Override
  public void close() {
    if (!accepting.compareAndSet(true, false)) {
      return;
    }
    try {
      request("shutdown", Map.of());
    } catch (ExternalStdioPluginException ignored) {
      // 关闭期间不重试、不重放；进程仍必须在同一共享 Deadline 内释放。
    } finally {
      transport.close(limits.shutdownTimeout());
    }
  }

  private JsonNode request(String method, Map<String, Object> params)
      throws ExternalStdioPluginException {
    String requestId = nextRequestId();
    String wire;
    try {
      wire =
          JSON.writeValueAsString(
              Map.of("requestId", requestId, "method", method, "params", params));
    } catch (RuntimeException failure) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
    requireFrame(wire);
    String response;
    try {
      response = transport.exchange(wire, limits.requestTimeout());
    } catch (TimeoutException timeout) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_TIMEOUT);
    } catch (IOException unavailable) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_PROCESS_EXITED);
    } catch (RuntimeException failure) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_EXECUTION_FAILED);
    }
    requireFrame(response);
    JsonNode root;
    try {
      root = JSON.readTree(response);
    } catch (RuntimeException invalid) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
    if (root == null
        || !root.isObject()
        || !requestId.equals(requiredText(root.get("requestId")))
        || !isTrue(root.get("ok"))
        || root.get("result") == null
        || !root.get("result").isObject()) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
    return root.get("result");
  }

  private void requireFrame(String value) throws ExternalStdioPluginException {
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length > limits.maxFrameBytes()) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
  }

  private String nextRequestId() throws ExternalStdioPluginException {
    String id = requestIds.next();
    if (id == null || !REQUEST_ID.matcher(id).matches()) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_PROTOCOL_INVALID);
    }
    return id;
  }

  private static PluginManifest parseManifest(JsonNode value) throws ExternalStdioPluginException {
    try {
      if (value == null || !value.isObject()) {
        throw new IllegalArgumentException();
      }
      JsonNode capabilities = value.get("capabilities");
      if (capabilities == null
          || !capabilities.isArray()
          || capabilities.size() > PluginCapability.values().length) {
        throw new IllegalArgumentException();
      }
      var parsedCapabilities = new ArrayList<PluginCapability>(capabilities.size());
      for (JsonNode capability : capabilities) {
        parsedCapabilities.add(PluginCapability.valueOf(requiredText(capability)));
      }
      return new PluginManifest(
          requiredInt(value.get("schemaVersion")),
          PluginId.parse(requiredText(value.get("id"))),
          requiredText(value.get("version")),
          requiredInt(value.get("apiVersion")),
          PluginKind.valueOf(requiredText(value.get("kind"))),
          List.copyOf(parsedCapabilities));
    } catch (RuntimeException invalid) {
      throw new ExternalStdioPluginException(PluginStableCode.PLUGIN_MANIFEST_INVALID);
    }
  }

  private static String method(PluginCapability capability) throws ExternalStdioPluginException {
    return switch (capability) {
      case TURN_TAP -> "turn.tap";
      case TOOL_TAP -> "tool.tap";
      case PROACTIVE_TAP -> "proactive.tap";
      case LIFECYCLE_TAP -> "lifecycle.tap";
    };
  }

  private static boolean isTrue(JsonNode value) {
    return value != null && value.isBoolean() && value.asBoolean();
  }

  private static String requiredText(JsonNode value) {
    if (value == null || !value.isString() || value.asString().isBlank()) {
      throw new IllegalArgumentException();
    }
    return value.asString();
  }

  private static int requiredInt(JsonNode value) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException();
    }
    return value.intValue();
  }

  private static void fail(PluginStableCode code) throws ExternalStdioPluginException {
    throw new ExternalStdioPluginException(code);
  }

  private void disableAfterFailure() {
    if (accepting.compareAndSet(true, false)) {
      transport.close(limits.shutdownTimeout());
    }
  }
}
