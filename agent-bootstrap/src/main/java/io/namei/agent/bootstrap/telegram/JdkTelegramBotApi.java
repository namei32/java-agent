package io.namei.agent.bootstrap.telegram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class JdkTelegramBotApi implements TelegramBotApi {
  public static final int MAX_POLL_RESPONSE_BYTES = 1024 * 1024;
  public static final int MAX_SEND_RESPONSE_BYTES = 64 * 1024;

  private static final URI OFFICIAL_BASE_URI = URI.create("https://api.telegram.org/");
  private static final int POLL_LIMIT = 20;
  private static final int MAX_SEND_TEXT_UNITS = 4096;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final URI baseUri;
  private final TelegramBotToken token;
  private final Duration pollRequestTimeout;
  private final Duration sendRequestTimeout;
  private final HttpClient http;

  public JdkTelegramBotApi(TelegramBotToken token, TelegramProperties properties) {
    this(
        OFFICIAL_BASE_URI,
        token,
        Objects.requireNonNull(properties, "properties").connectTimeout(),
        properties.pollRequestTimeout(),
        properties.sendRequestTimeout());
  }

  JdkTelegramBotApi(
      URI baseUri,
      TelegramBotToken token,
      Duration connectTimeout,
      Duration pollRequestTimeout,
      Duration sendRequestTimeout) {
    this.baseUri = requireBaseUri(baseUri);
    this.token = Objects.requireNonNull(token, "token");
    requirePositiveBounded(
        connectTimeout, TelegramProperties.MAX_CONNECT_TIMEOUT, "connectTimeout");
    requirePositiveBounded(
        pollRequestTimeout, TelegramProperties.MAX_POLL_REQUEST_TIMEOUT, "pollRequestTimeout");
    requirePositiveBounded(
        sendRequestTimeout, TelegramProperties.MAX_SEND_REQUEST_TIMEOUT, "sendRequestTimeout");
    this.pollRequestTimeout = pollRequestTimeout;
    this.sendRequestTimeout = sendRequestTimeout;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public List<TelegramUpdate> getUpdates(long offset, Duration longPollTimeout) {
    if (offset < 0) {
      throw new IllegalArgumentException("Telegram offset 不能为负数");
    }
    requirePositiveBounded(
        longPollTimeout, TelegramProperties.MAX_LONG_POLL_TIMEOUT, "longPollTimeout");
    if (longPollTimeout.compareTo(pollRequestTimeout) >= 0) {
      throw new IllegalArgumentException("longPollTimeout 必须小于 pollRequestTimeout");
    }
    long timeoutSeconds = longPollTimeout.getSeconds() + (longPollTimeout.getNano() == 0 ? 0L : 1L);
    String body =
        JSON.writeValueAsString(
            Map.of(
                "offset",
                offset,
                "limit",
                POLL_LIMIT,
                "timeout",
                timeoutSeconds,
                "allowed_updates",
                List.of("message")));
    JsonNode result =
        invoke("getUpdates", body, pollRequestTimeout, MAX_POLL_RESPONSE_BYTES, false);
    if (!result.isArray() || result.size() > POLL_LIMIT) {
      throw invalidResponse();
    }
    var updates = new ArrayList<TelegramUpdate>(result.size());
    for (JsonNode item : result) {
      updates.add(parseUpdate(item));
    }
    return List.copyOf(updates);
  }

  @Override
  public TelegramSendReceipt sendMessage(long chatId, String text) {
    if (chatId <= 0) {
      throw new IllegalArgumentException("Telegram chatId 必须为正数");
    }
    if (text == null || text.isEmpty()) {
      throw new IllegalArgumentException("Telegram text 不能为空");
    }
    if (text.length() > MAX_SEND_TEXT_UNITS) {
      throw new IllegalArgumentException("Telegram text 超过长度上限");
    }
    String body = JSON.writeValueAsString(Map.of("chat_id", chatId, "text", text));
    JsonNode result =
        invoke("sendMessage", body, sendRequestTimeout, MAX_SEND_RESPONSE_BYTES, true);
    if (!result.isObject()) {
      throw invalidResponse();
    }
    long messageId = requiredLong(result, "message_id");
    if (messageId <= 0) {
      throw invalidResponse();
    }
    return new TelegramSendReceipt(messageId);
  }

  private JsonNode invoke(
      String method, String body, Duration timeout, int maxResponseBytes, boolean send) {
    HttpRequest request =
        HttpRequest.newBuilder(endpoint(method))
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<InputStream> response;
    long startedAt = System.nanoTime();
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
    } catch (IOException failure) {
      throw new TelegramApiException(
          failure instanceof HttpTimeoutException
              ? TelegramApiException.Reason.TIMEOUT
              : TelegramApiException.Reason.UNAVAILABLE);
    }

    long remaining = timeout.toNanos() - (System.nanoTime() - startedAt);
    if (remaining <= 0) {
      closeQuietly(response.body());
      throw new TelegramApiException(TelegramApiException.Reason.TIMEOUT);
    }
    byte[] responseBody = readBounded(response.body(), maxResponseBytes, remaining);
    return requireSuccessfulResult(response.statusCode(), responseBody, send);
  }

  private JsonNode requireSuccessfulResult(int status, byte[] body, boolean send) {
    if (status == 401 || status == 403 || status == 404) {
      throw new TelegramApiException(TelegramApiException.Reason.UNAUTHORIZED);
    }
    if (status == 429) {
      throw rateLimited(parseOptional(body));
    }
    if (status >= 500 && status <= 599) {
      throw new TelegramApiException(TelegramApiException.Reason.UNAVAILABLE);
    }
    if (status < 200 || status >= 300) {
      if (send && status >= 400 && status <= 499) {
        throw new TelegramApiException(TelegramApiException.Reason.PERMANENT_REJECTION);
      }
      throw invalidResponse();
    }

    JsonNode root = parse(body);
    JsonNode ok = root.get("ok");
    if (!root.isObject() || ok == null || !ok.isBoolean()) {
      throw invalidResponse();
    }
    if (!ok.asBoolean()) {
      throw apiFailure(root, send);
    }
    JsonNode result = root.get("result");
    if (result == null || result.isNull()) {
      throw invalidResponse();
    }
    return result;
  }

  private static TelegramApiException apiFailure(JsonNode root, boolean send) {
    int errorCode = optionalErrorCode(root);
    if (errorCode == 401 || errorCode == 403 || errorCode == 404) {
      return new TelegramApiException(TelegramApiException.Reason.UNAUTHORIZED);
    }
    if (errorCode == 429) {
      return rateLimited(root);
    }
    if (errorCode >= 500 && errorCode <= 599) {
      return new TelegramApiException(TelegramApiException.Reason.UNAVAILABLE);
    }
    if (send && errorCode >= 400 && errorCode <= 499) {
      return new TelegramApiException(TelegramApiException.Reason.PERMANENT_REJECTION);
    }
    return invalidResponse();
  }

  private static TelegramApiException rateLimited(JsonNode root) {
    Duration retryAfter = null;
    if (root != null && root.isObject()) {
      JsonNode parameters = root.get("parameters");
      JsonNode seconds =
          parameters == null || !parameters.isObject() ? null : parameters.get("retry_after");
      if (isLong(seconds)) {
        long value = seconds.longValue();
        if (value > 0 && value <= TelegramProperties.MAX_RETRY_AFTER.toSeconds()) {
          retryAfter = Duration.ofSeconds(value);
        }
      }
    }
    return new TelegramApiException(TelegramApiException.Reason.RATE_LIMITED, retryAfter);
  }

  private static TelegramUpdate parseUpdate(JsonNode item) {
    if (item == null || !item.isObject()) {
      throw invalidResponse();
    }
    long updateId = requiredLong(item, "update_id");
    if (updateId < 0) {
      throw invalidResponse();
    }
    JsonNode rawMessage = item.get("message");
    if (rawMessage == null || rawMessage.isNull()) {
      return new TelegramUpdate(updateId, null);
    }
    if (!rawMessage.isObject()) {
      throw invalidResponse();
    }
    return new TelegramUpdate(updateId, parseMessage(rawMessage));
  }

  private static TelegramMessage parseMessage(JsonNode message) {
    long messageId = optionalLong(message, "message_id", 0);
    Long epochSeconds = optionalNullableLong(message, "date");
    Instant occurredAt = null;
    if (epochSeconds != null) {
      try {
        occurredAt = Instant.ofEpochSecond(epochSeconds);
      } catch (DateTimeException invalid) {
        throw invalidResponse();
      }
    }

    JsonNode chat = optionalObject(message, "chat");
    long chatId = chat == null ? 0 : optionalLong(chat, "id", 0);
    String chatType = chat == null ? null : optionalText(chat, "type");
    JsonNode sender = optionalObject(message, "from");
    long senderId = sender == null ? 0 : optionalLong(sender, "id", 0);
    boolean senderBot = sender == null || optionalBoolean(sender, "is_bot", true);
    String text = optionalText(message, "text");
    return new TelegramMessage(messageId, occurredAt, chatId, chatType, senderId, senderBot, text);
  }

  private static JsonNode optionalObject(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw invalidResponse();
    }
    return value;
  }

  private static String optionalText(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw invalidResponse();
    }
    return value.asString();
  }

  private static boolean optionalBoolean(JsonNode parent, String field, boolean fallback) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isBoolean()) {
      throw invalidResponse();
    }
    return value.asBoolean();
  }

  private static Long optionalNullableLong(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!isLong(value)) {
      throw invalidResponse();
    }
    return value.longValue();
  }

  private static long optionalLong(JsonNode parent, String field, long fallback) {
    Long value = optionalNullableLong(parent, field);
    return value == null ? fallback : value;
  }

  private static long requiredLong(JsonNode parent, String field) {
    Long value = optionalNullableLong(parent, field);
    if (value == null) {
      throw invalidResponse();
    }
    return value;
  }

  private static boolean isLong(JsonNode value) {
    return value != null && value.isIntegralNumber() && value.canConvertToLong();
  }

  private static int optionalErrorCode(JsonNode root) {
    JsonNode errorCode = root.get("error_code");
    if (!isLong(errorCode)) {
      return 0;
    }
    long value = errorCode.longValue();
    return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (int) value : 0;
  }

  private static JsonNode parse(byte[] body) {
    JsonNode parsed = parseOptional(body);
    if (parsed == null) {
      throw invalidResponse();
    }
    return parsed;
  }

  private static JsonNode parseOptional(byte[] body) {
    try {
      return JSON.readTree(body);
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static byte[] readBounded(InputStream input, int maximum, long timeoutNanos) {
    var task = new FutureTask<byte[]>(() -> readAllBounded(input, maximum));
    Thread reader = Thread.ofVirtual().name("telegram-http-body-reader").start(task);
    try {
      return task.get(timeoutNanos, TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      closeQuietly(input);
      task.cancel(true);
      reader.interrupt();
      Thread.currentThread().interrupt();
      throw new TelegramApiException(TelegramApiException.Reason.INTERRUPTED);
    } catch (TimeoutException timeout) {
      closeQuietly(input);
      task.cancel(true);
      reader.interrupt();
      throw new TelegramApiException(TelegramApiException.Reason.TIMEOUT);
    } catch (ExecutionException failedRead) {
      Throwable failure = failedRead.getCause();
      if (failure instanceof TelegramApiException apiFailure) {
        throw apiFailure;
      }
      throw new TelegramApiException(
          failure instanceof HttpTimeoutException
              ? TelegramApiException.Reason.TIMEOUT
              : TelegramApiException.Reason.UNAVAILABLE);
    }
  }

  private static byte[] readAllBounded(InputStream input, int maximum) throws IOException {
    try (input) {
      return collectBounded(input, maximum);
    }
  }

  private static byte[] collectBounded(InputStream input, int maximum) throws IOException {
    var output = new ByteArrayOutputStream(Math.min(maximum, 8192));
    var buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      if (read > maximum - total) {
        throw invalidResponse();
      }
      output.write(buffer, 0, read);
      total += read;
    }
    return output.toByteArray();
  }

  private static void closeQuietly(InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      // Closing is best-effort after the public result has already been fixed.
    }
  }

  private URI endpoint(String method) {
    return baseUri.resolve("/bot" + token.value() + "/" + method);
  }

  private static URI requireBaseUri(URI value) {
    if (value == null
        || value.getHost() == null
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null
        || !(value.getPath().isEmpty() || value.getPath().equals("/"))
        || !("http".equals(value.getScheme()) || "https".equals(value.getScheme()))) {
      throw new IllegalArgumentException("Telegram API Base URI 无效");
    }
    String normalized = value.toString();
    return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
  }

  private static void requirePositiveBounded(Duration value, Duration maximum, String field) {
    if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(field + " 超出允许范围");
    }
  }

  private static TelegramApiException invalidResponse() {
    return new TelegramApiException(TelegramApiException.Reason.INVALID_RESPONSE);
  }
}
