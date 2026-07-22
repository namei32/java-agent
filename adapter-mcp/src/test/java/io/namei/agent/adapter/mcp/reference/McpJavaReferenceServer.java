package io.namei.agent.adapter.mcp.reference;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 仓库自持的 MCP stdio 参考 Server，仅作为测试子进程运行。 */
public final class McpJavaReferenceServer {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Object WRITE_LOCK = new Object();
  private static final AtomicReference<Object> SLOW_REQUEST_ID = new AtomicReference<>();
  private static List<String> scenarioArguments = List.of();

  private McpJavaReferenceServer() {}

  public static void main(String[] arguments) throws Exception {
    String scenario = arguments.length == 0 ? "normal" : arguments[0];
    scenarioArguments =
        arguments.length <= 1 ? List.of() : List.of(arguments).subList(1, arguments.length);
    if ("kill-on-close".equals(scenario)) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      Thread.sleep(30_000);
                    } catch (InterruptedException exception) {
                      Thread.currentThread().interrupt();
                    }
                  }));
    }
    if ("stderr-flood".equals(scenario)) {
      System.err.write(new byte[262_144]);
      System.err.flush();
    }

    try (var reader =
            new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8), 16 * 1024);
        var writer = new PrintWriter(System.out, false, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        JsonNode message = JSON.readTree(line);
        handle(scenario, message, writer);
      }
    }
  }

  private static void handle(String scenario, JsonNode message, PrintWriter writer)
      throws Exception {
    String method = message.path("method").asString();
    Object id = message.has("id") ? scalar(message.path("id")) : null;
    switch (method) {
      case "initialize" -> initialize(scenario, id, writer);
      case "notifications/initialized" -> {
        // 生命周期通知没有响应。
      }
      case "tools/list" -> listTools(scenario, message, id, writer);
      case "resources/list" -> listResources(scenario, message, id, writer);
      case "prompts/list" -> listPrompts(scenario, message, id, writer);
      case "tools/call" -> callTool(scenario, message, id, writer);
      case "notifications/cancelled" -> cancel(message, writer);
      default -> {
        if (id != null) {
          send(
              writer,
              Map.of(
                  "jsonrpc",
                  "2.0",
                  "id",
                  id,
                  "error",
                  Map.of("code", -32601, "message", "private-reference-server-message")));
        }
      }
    }
  }

  private static void initialize(String scenario, Object id, PrintWriter writer) throws Exception {
    if ("oversized-wire".equals(scenario)) {
      synchronized (WRITE_LOCK) {
        writer.println("x".repeat(131_072));
        writer.flush();
      }
      return;
    }
    if ("malformed-json".equals(scenario) || "stdout-noise".equals(scenario)) {
      synchronized (WRITE_LOCK) {
        writer.println(
            "malformed-json".equals(scenario) ? "{private-malformed-json" : "private-stdout-noise");
        writer.flush();
      }
      return;
    }
    if ("slow-reconnect".equals(scenario) && markerExists(0)) {
      Thread.sleep(600);
    }
    Object responseId = "wrong-id".equals(scenario) ? Objects.requireNonNull(id) + "-wrong" : id;
    Map<String, Object> capabilities =
        "tools-only".equals(scenario)
            ? Map.of("tools", Map.of("listChanged", true))
            : Map.of(
                "tools", Map.of("listChanged", true),
                "resources", Map.of("listChanged", true),
                "prompts", Map.of("listChanged", true));
    send(
        writer,
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            Objects.requireNonNull(responseId),
            "result",
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                capabilities,
                "serverInfo",
                Map.of("name", "java-reference", "version", "1.0.0"))));
  }

  private static void listTools(String scenario, JsonNode message, Object id, PrintWriter writer)
      throws Exception {
    JsonNode cursorNode = message.path("params").path("cursor");
    String cursor =
        cursorNode.isMissingNode() || cursorNode.isNull() ? null : cursorNode.asString();
    if (cursor == null) {
      send(
          writer,
          Map.of(
              "jsonrpc",
              "2.0",
              "method",
              "notifications/test/interleaved",
              "params",
              Map.of("phase", "first-page")));
      send(
          writer,
          Map.of(
              "jsonrpc",
              "2.0",
              "id",
              Objects.requireNonNull(id),
              "result",
              Map.of(
                  "tools",
                  List.of(
                      tool("echo", echoSchema(), catalogDescriptionSuffix(scenario)),
                      tool("slow", Map.of(), catalogDescriptionSuffix(scenario))),
                  "nextCursor",
                  "page-2")));
      return;
    }
    send(
        writer,
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            Objects.requireNonNull(id),
            "result",
            Map.of(
                "tools",
                List.of(
                    tool("remote_error", Map.of(), catalogDescriptionSuffix(scenario)),
                    tool("image", Map.of(), catalogDescriptionSuffix(scenario)),
                    tool("env_probe", Map.of(), catalogDescriptionSuffix(scenario))))));
  }

  private static void callTool(String scenario, JsonNode message, Object id, PrintWriter writer) {
    String name = message.path("params").path("name").asString();
    JsonNode toolArguments = message.path("params").path("arguments");
    Thread.ofVirtual()
        .name("mcp-reference-call")
        .start(
            () -> {
              try {
                switch (name) {
                  case "echo" -> {
                    if (("sudden-until-marker".equals(scenario)
                            || "catalog-change".equals(scenario)
                            || "slow-reconnect".equals(scenario))
                        && !markerExists(0)) {
                      Runtime.getRuntime().halt(74);
                    }
                    long delay = toolArguments.path("delayMillis").asLong(0);
                    if (delay > 0) {
                      Thread.sleep(Math.min(delay, 2_000));
                    }
                    String text = toolArguments.path("text").asString();
                    if ("list-changed".equals(scenario)) {
                      send(
                          writer,
                          Map.of("jsonrpc", "2.0", "method", "notifications/tools/list_changed"));
                    }
                    if ("assets-list-changed".equals(scenario)) {
                      send(
                          writer,
                          Map.of(
                              "jsonrpc", "2.0", "method", "notifications/resources/list_changed"));
                    }
                    result(writer, id, List.of(Map.of("type", "text", "text", text)), false);
                  }
                  case "remote_error" ->
                      result(
                          writer,
                          id,
                          List.of(Map.of("type", "text", "text", "private-reference-server-error")),
                          true);
                  case "image" ->
                      result(
                          writer,
                          id,
                          List.of(
                              Map.of(
                                  "type", "image",
                                  "data", "cHJpdmF0ZQ==",
                                  "mimeType", "image/png")),
                          false);
                  case "env_probe" ->
                      result(
                          writer,
                          id,
                          List.of(
                              Map.of(
                                  "type",
                                  "text",
                                  "text",
                                  String.join(
                                      ",", System.getenv().keySet().stream().sorted().toList()))),
                          false);
                  case "slow" -> {
                    SLOW_REQUEST_ID.set(id);
                    send(
                        writer,
                        Map.of(
                            "jsonrpc",
                            "2.0",
                            "method",
                            "notifications/test/call_started",
                            "params",
                            Map.of("started", true)));
                  }
                  default ->
                      send(
                          writer,
                          Map.of(
                              "jsonrpc",
                              "2.0",
                              "id",
                              Objects.requireNonNull(id),
                              "error",
                              Map.of("code", -32602, "message", "private-unknown-tool-message")));
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } catch (Exception exception) {
                Runtime.getRuntime().halt(72);
              }
            });
  }

  private static void listResources(
      String scenario, JsonNode message, Object id, PrintWriter writer) throws Exception {
    if ("assets-disabled-probe".equals(scenario)) {
      Runtime.getRuntime().halt(76);
    }
    if (!assetCatalogScenario(scenario)) {
      send(
          writer,
          Map.of(
              "jsonrpc",
              "2.0",
              "id",
              Objects.requireNonNull(id),
              "result",
              Map.of("resources", List.of())));
      return;
    }
    String cursor = message.path("params").path("cursor").asString(null);
    if (cursor == null) {
      send(
          writer,
          Map.of(
              "jsonrpc",
              "2.0",
              "id",
              Objects.requireNonNull(id),
              "result",
              Map.of(
                  "resources",
                  List.of(
                      Map.of(
                          "uri", "file:///docs/first.md",
                          "name", "First resource",
                          "description", "First page resource")),
                  "nextCursor",
                  "resources-page-2")));
      return;
    }
    send(
        writer,
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            Objects.requireNonNull(id),
            "result",
            Map.of(
                "resources",
                List.of(
                    Map.of(
                        "uri", "file:///docs/second.md",
                        "name", "Second resource",
                        "description", "Second page resource")))));
  }

  private static void listPrompts(String scenario, JsonNode message, Object id, PrintWriter writer)
      throws Exception {
    List<Map<String, Object>> prompts;
    if (assetCatalogScenario(scenario)) {
      prompts =
          List.of(
              Map.of(
                  "name",
                  "release_notes",
                  "description",
                  "Release summaries",
                  "arguments",
                  List.of(Map.of("name", "version", "required", true))));
    } else if ("assets-too-many-prompts".equals(scenario)) {
      prompts =
          java.util.stream.IntStream.range(0, 33)
              .mapToObj(
                  index ->
                      Map.<String, Object>of(
                          "name", "prompt_" + index, "description", "Public prompt"))
              .toList();
    } else {
      prompts = List.of();
    }
    send(
        writer,
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            Objects.requireNonNull(id),
            "result",
            Map.of("prompts", prompts)));
  }

  private static void cancel(JsonNode message, PrintWriter writer) throws Exception {
    Object requestId = scalar(message.path("params").path("requestId"));
    Object slowId = SLOW_REQUEST_ID.get();
    boolean matches = slowId != null && slowId.equals(requestId);
    send(
        writer,
        Map.of(
            "jsonrpc",
            "2.0",
            "method",
            "notifications/test/cancel_observed",
            "params",
            Map.of("matches", matches)));
    if (matches) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  Thread.sleep(75);
                  result(
                      writer,
                      slowId,
                      List.of(Map.of("type", "text", "text", "late-private-result")),
                      false);
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                } catch (Exception exception) {
                  Runtime.getRuntime().halt(73);
                }
              });
    }
  }

  private static Map<String, Object> tool(
      String name, Map<String, Object> schema, String descriptionSuffix) {
    return Map.of(
        "name",
        name,
        "description",
        "Java reference " + name + descriptionSuffix,
        "inputSchema",
        schema,
        "annotations",
        Map.of("readOnlyHint", false));
  }

  private static String catalogDescriptionSuffix(String scenario) {
    return "catalog-change".equals(scenario) && markerExists(1) ? " changed" : "";
  }

  private static boolean assetCatalogScenario(String scenario) {
    return "assets-paginated".equals(scenario) || "assets-list-changed".equals(scenario);
  }

  private static boolean markerExists(int index) {
    return scenarioArguments.size() > index && Files.exists(Path.of(scenarioArguments.get(index)));
  }

  private static Map<String, Object> echoSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "text", Map.of("type", "string"),
            "delayMillis", Map.of("type", "integer")),
        "required",
        List.of("text"),
        "additionalProperties",
        false);
  }

  private static void result(
      PrintWriter writer, Object id, List<Map<String, Object>> content, boolean isError)
      throws Exception {
    send(
        writer,
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            Objects.requireNonNull(id),
            "result",
            Map.of("content", content, "isError", isError)));
  }

  private static Object scalar(JsonNode node) {
    if (node.isIntegralNumber()) {
      return node.asLong();
    }
    return node.asString();
  }

  private static void send(PrintWriter writer, Map<String, Object> message) throws IOException {
    String json = JSON.writeValueAsString(message);
    synchronized (WRITE_LOCK) {
      writer.println(json);
      writer.flush();
    }
  }
}
