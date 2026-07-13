package io.namei.agent.adapter.springai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OpenAiStubServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private volatile Response response = new Response(200, successBody("回答"), Duration.ZERO);

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
    response = new Response(status, body, Duration.ZERO);
  }

  void respondAfter(Duration delay, String body) {
    response = new Response(200, body, delay);
  }

  static String successBody(String content) {
    return """
        {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """
        .formatted(content);
  }

  private void handle(HttpExchange exchange) throws IOException {
    Response selected = response;
    try {
      Thread.sleep(selected.delay());
      byte[] body = selected.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(selected.status(), body.length);
      exchange.getResponseBody().write(body);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }

  private record Response(int status, String body, Duration delay) {}
}
