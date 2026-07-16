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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class TelegramBotApiStubServer implements AutoCloseable {
  private static final String EMPTY_UPDATES = "{\"ok\":true,\"result\":[]}";
  private static final String SENT = "{\"ok\":true,\"result\":{\"message_id\":43}}";

  private final HttpServer server;
  private final ExecutorService executor = boundedExecutor();
  private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
  private final ArrayBlockingQueue<Response> pollResponses = new ArrayBlockingQueue<>(32);
  private final ArrayBlockingQueue<Response> sendResponses = new ArrayBlockingQueue<>(32);
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
    pollResponses.clear();
    sendResponses.clear();
    pollResponse = Response.immediate(200, EMPTY_UPDATES);
    sendResponse = Response.immediate(200, SENT);
    requestObserved = new CountDownLatch(1);
  }

  void respondToPoll(int status, String body) {
    pollResponse = Response.immediate(status, body);
  }

  void respondToPollAfter(Duration delay, int status, String body) {
    pollResponse = new Response(status, body, delay, Duration.ZERO, null);
  }

  void respondToPollBodyAfter(Duration delay, int status, String body) {
    pollResponse = new Response(status, body, Duration.ZERO, delay, null);
  }

  void respondToPollWhen(CountDownLatch release, int status, String body) {
    pollResponse = new Response(status, body, Duration.ZERO, Duration.ZERO, release);
  }

  void respondToSend(int status, String body) {
    sendResponse = Response.immediate(status, body);
  }

  void enqueuePoll(int status, String body) {
    pollResponses.add(Response.immediate(status, body));
  }

  void enqueuePollWhen(CountDownLatch release, int status, String body) {
    pollResponses.add(new Response(status, body, Duration.ZERO, Duration.ZERO, release));
  }

  void enqueueSend(int status, String body) {
    sendResponses.add(Response.immediate(status, body));
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
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Fake Telegram Server 未停止");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Fake Telegram Server 关闭被中断");
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    Response selected;
    if (path.endsWith("/getUpdates")) {
      Response scripted = pollResponses.poll();
      selected = scripted == null ? pollResponse : scripted;
    } else if (path.endsWith("/sendMessage")) {
      Response scripted = sendResponses.poll();
      selected = scripted == null ? sendResponse : scripted;
    } else {
      selected = Response.immediate(404, "{\"ok\":false}");
    }
    try {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(
          new Request(
              exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
      requestObserved.countDown();
      if (selected.release() != null) {
        selected.release().await();
      }
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

  private static ExecutorService boundedExecutor() {
    return new ThreadPoolExecutor(
        4,
        4,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32),
        Thread.ofVirtual().name("telegram-fake-server-worker-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  private record Response(
      int status, String body, Duration headerDelay, Duration bodyDelay, CountDownLatch release) {
    private static Response immediate(int status, String body) {
      return new Response(status, body, Duration.ZERO, Duration.ZERO, null);
    }
  }
}
