package io.namei.agent.adapter.springai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class OpenAiStubServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final CopyOnWriteArrayList<String> requestBodies = new CopyOnWriteArrayList<>();
  private final AtomicBoolean clientDisconnected = new AtomicBoolean();
  private final AtomicBoolean internalCorrelationHeaderObserved = new AtomicBoolean();
  private volatile Response response = Response.json(200, successBody("回答"), Duration.ZERO);

  OpenAiStubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", this::handle);
    server.setExecutor(executor);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  void respond(int status, String body) {
    response = Response.json(status, body, Duration.ZERO);
  }

  void respondAfter(Duration delay, String body) {
    response = Response.json(200, body, delay);
  }

  void respondSse(List<String> events) {
    respondSse(Duration.ZERO, events);
  }

  void respondSse(Duration eventDelay, List<String> events) {
    response = Response.sse(events, eventDelay);
  }

  void respondConcurrentSse(int expectedConnections, Duration eventDelay, List<String> events) {
    response = Response.concurrentSse(expectedConnections, events, eventDelay);
  }

  void reset() {
    requestBodies.clear();
    clientDisconnected.set(false);
    internalCorrelationHeaderObserved.set(false);
    response = Response.json(200, successBody("回答"), Duration.ZERO);
  }

  List<String> requestBodies() {
    return List.copyOf(requestBodies);
  }

  boolean clientDisconnected() {
    return clientDisconnected.get();
  }

  boolean internalCorrelationHeaderObserved() {
    return internalCorrelationHeaderObserved.get();
  }

  static String successBody(String content) {
    return """
    {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"test-model",
     "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
    """
        .formatted(content);
  }

  static String toolCallBody(String callId, String toolName, String arguments) {
    return """
    {"id":"chatcmpl-tool","object":"chat.completion","created":1,"model":"test-model",
     "choices":[{"index":0,"message":{"role":"assistant","content":null,
     "tool_calls":[{"id":"%s","type":"function","function":{"name":"%s","arguments":"%s"}}]},
     "finish_reason":"tool_calls"}],
     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
    """
        .formatted(callId, toolName, arguments.replace("\"", "\\\""));
  }

  static String textDelta(String content) {
    return """
    {"id":"chatcmpl-stream","object":"chat.completion.chunk","created":1,"model":"test-model",
     "choices":[{"index":0,"delta":{"role":"assistant","content":"%s"},"finish_reason":null}]}
    """
        .formatted(content.replace("\"", "\\\"").replace("\n", "\\n"));
  }

  static String finished(String reason) {
    return """
    {"id":"chatcmpl-stream","object":"chat.completion.chunk","created":1,"model":"test-model",
     "choices":[{"index":0,"delta":{},"finish_reason":"%s"}]}
    """
        .formatted(reason);
  }

  static String toolCallStart(String callId, String toolName) {
    return """
    {"id":"chatcmpl-stream","object":"chat.completion.chunk","created":1,"model":"test-model",
     "choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"%s",
     "type":"function","function":{"name":"%s","arguments":""}}]},"finish_reason":null}]}
    """
        .formatted(callId, toolName);
  }

  static String toolArguments(String arguments) {
    return """
    {"id":"chatcmpl-stream","object":"chat.completion.chunk","created":1,"model":"test-model",
     "choices":[{"index":0,"delta":{"tool_calls":[{"index":0,
     "function":{"arguments":"%s"}}]},"finish_reason":null}]}
    """
        .formatted(arguments.replace("\"", "\\\""));
  }

  private void handle(HttpExchange exchange) throws IOException {
    Response selected = response;
    try {
      if (exchange
          .getRequestHeaders()
          .containsKey(OpenAiStreamCancellationRegistry.CORRELATION_HEADER)) {
        internalCorrelationHeaderObserved.set(true);
      }
      requestBodies.add(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      Thread.sleep(selected.initialDelay());
      exchange.getResponseHeaders().set("Content-Type", selected.contentType());
      if (selected.events().isEmpty()) {
        byte[] body = selected.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(selected.status(), body.length);
        exchange.getResponseBody().write(body);
      } else {
        exchange.sendResponseHeaders(selected.status(), 0);
        selected.awaitConnections();
        for (String event : selected.events()) {
          writeEvent(exchange, event);
          Thread.sleep(selected.eventDelay());
        }
        writeEvent(exchange, "[DONE]");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (IOException exception) {
      clientDisconnected.set(true);
    } finally {
      exchange.close();
    }
  }

  private static void writeEvent(HttpExchange exchange, String event) throws IOException {
    String payload = event.strip().replace("\r", "").replace("\n", "");
    exchange
        .getResponseBody()
        .write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
    exchange.getResponseBody().flush();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }

  private record Response(
      int status,
      String body,
      Duration initialDelay,
      String contentType,
      List<String> events,
      Duration eventDelay,
      CountDownLatch connectionsReady) {
    private Response {
      events = List.copyOf(events);
    }

    private static Response json(int status, String body, Duration delay) {
      return new Response(
          status, body, delay, "application/json", List.of(), Duration.ZERO, new CountDownLatch(0));
    }

    private static Response sse(List<String> events, Duration eventDelay) {
      return new Response(
          200, "", Duration.ZERO, "text/event-stream", events, eventDelay, new CountDownLatch(0));
    }

    private static Response concurrentSse(
        int expectedConnections, List<String> events, Duration eventDelay) {
      if (expectedConnections < 2) {
        throw new IllegalArgumentException("expectedConnections must be at least 2");
      }
      return new Response(
          200,
          "",
          Duration.ZERO,
          "text/event-stream",
          events,
          eventDelay,
          new CountDownLatch(expectedConnections));
    }

    private void awaitConnections() throws IOException, InterruptedException {
      connectionsReady.countDown();
      if (!connectionsReady.await(10, TimeUnit.SECONDS)) {
        throw new IOException("Timed out waiting for concurrent SSE connections");
      }
    }
  }
}
