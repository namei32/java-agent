package io.namei.agent.bootstrap.telegram;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class TelegramBotApiStubServer implements AutoCloseable {
  private static final String EMPTY_UPDATES = "{\"ok\":true,\"result\":[]}";
  private static final String SENT = "{\"ok\":true,\"result\":{\"message_id\":43}}";

  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
  private volatile Response pollResponse = Response.immediate(200, EMPTY_UPDATES);
  private volatile Response sendResponse = Response.immediate(200, SENT);
  private volatile CountDownLatch requestObserved = new CountDownLatch(1);

  TelegramBotApiStubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(executor);
    server.start();
  }

  URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  void reset() {
    requests.clear();
    pollResponse = Response.immediate(200, EMPTY_UPDATES);
    sendResponse = Response.immediate(200, SENT);
    requestObserved = new CountDownLatch(1);
  }

  void respondToPoll(int status, String body) {
    pollResponse = Response.immediate(status, body);
  }

  void respondToPollAfter(Duration delay, int status, String body) {
    pollResponse = new Response(status, body, delay, Duration.ZERO);
  }

  void respondToPollBodyAfter(Duration delay, int status, String body) {
    pollResponse = new Response(status, body, Duration.ZERO, delay);
  }

  void respondToSend(int status, String body) {
    sendResponse = Response.immediate(status, body);
  }

  List<Request> requests() {
    return List.copyOf(requests);
  }

  boolean awaitRequest(Duration timeout) throws InterruptedException {
    return requestObserved.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    Response selected;
    if (path.endsWith("/getUpdates")) {
      selected = pollResponse;
    } else if (path.endsWith("/sendMessage")) {
      selected = sendResponse;
    } else {
      selected = Response.immediate(404, "{\"ok\":false}");
    }
    try {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(
          new Request(
              exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
      requestObserved.countDown();
      Thread.sleep(selected.headerDelay());
      byte[] responseBody = selected.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(selected.status(), responseBody.length);
      Thread.sleep(selected.bodyDelay());
      exchange.getResponseBody().write(responseBody);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  record Request(String method, String path, Map<String, List<String>> headers, String body) {
    String firstHeader(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream())
          .findFirst()
          .orElse("");
    }
  }

  private record Response(int status, String body, Duration headerDelay, Duration bodyDelay) {
    private static Response immediate(int status, String body) {
      return new Response(status, body, Duration.ZERO, Duration.ZERO);
    }
  }
}
