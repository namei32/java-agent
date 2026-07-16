package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JdkTelegramBotApiIT {
  private static final String FAKE_TOKEN = "123456:TEST_TOKEN_12345678901234567890";
  private static final String REMOTE_SECRET = "remote-secret-description";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TelegramBotApiStubServer SERVER = createServer();

  @BeforeEach
  void resetServer() {
    SERVER.reset();
  }

  @AfterAll
  static void stopServer() {
    SERVER.close();
  }

  @Test
  void validatesAndRedactsBotTokensLocally() {
    TelegramBotToken token = new TelegramBotToken(FAKE_TOKEN);

    assertThat(token.toString()).isEqualTo("TelegramBotToken[value=<redacted>]");
    assertThat(token.toString()).doesNotContain(FAKE_TOKEN);
    for (String invalid :
        List.of(
            "",
            "123456",
            "0:TEST_TOKEN_12345678901234567890",
            "123456:short",
            "123456:bad/token_12345678901234567890")) {
      assertThatThrownBy(() -> new TelegramBotToken(invalid))
          .as(invalid)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Telegram Bot Token 格式无效");
    }
  }

  @Test
  void postsUtf8LongPollContractAndParsesOnlyWhitelistedFields() throws Exception {
    SERVER.respondToPoll(
        200,
        """
        {"ok":true,"unknown":"ignored","result":[
          {"update_id":501,"unknown_update_field":"ignored","message":{
            "message_id":42,"date":1784160000,"text":"你好，Telegram",
            "chat":{"id":10001,"type":"private","title":"must-not-project"},
            "from":{"id":10001,"is_bot":false,"username":"must-not-authorize"},
            "unknown_message_field":{"secret":"must-not-project"}
          }}]}
        """);

    List<TelegramUpdate> updates = client().getUpdates(500, Duration.ofSeconds(1));

    assertThat(updates).hasSize(1);
    TelegramUpdate update = updates.getFirst();
    assertThat(update.updateId()).isEqualTo(501);
    assertThat(update.message().messageId()).isEqualTo(42);
    assertThat(update.message().occurredAt()).isEqualTo(Instant.ofEpochSecond(1784160000));
    assertThat(update.message().chatId()).isEqualTo(10001);
    assertThat(update.message().chatType()).isEqualTo("private");
    assertThat(update.message().senderId()).isEqualTo(10001);
    assertThat(update.message().senderBot()).isFalse();
    assertThat(update.message().text()).isEqualTo("你好，Telegram");

    TelegramBotApiStubServer.Request request = SERVER.requests().getFirst();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/bot" + FAKE_TOKEN + "/getUpdates");
    assertThat(request.firstHeader("Content-Type")).isEqualTo("application/json; charset=UTF-8");
    JsonNode body = JSON.readTree(request.body());
    assertThat(body.path("offset").asLong()).isEqualTo(500);
    assertThat(body.path("limit").asInt()).isEqualTo(20);
    assertThat(body.path("timeout").asInt()).isEqualTo(1);
    assertThat(body.path("allowed_updates")).hasSize(1);
    assertThat(body.path("allowed_updates").get(0).asString()).isEqualTo("message");
  }

  @Test
  void sendsPlainUtf8TextWithoutParseModeOrImplicitRetry() throws Exception {
    SERVER.respondToSend(
        200, "{\"ok\":true,\"result\":{\"message_id\":43},\"unknown\":\"ignored\"}");

    client().sendMessage(10001, "纯文本 <b>不解析</b> 😊");

    assertThat(SERVER.requests()).hasSize(1);
    TelegramBotApiStubServer.Request request = SERVER.requests().getFirst();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/bot" + FAKE_TOKEN + "/sendMessage");
    JsonNode body = JSON.readTree(request.body());
    assertThat(body.path("chat_id").asLong()).isEqualTo(10001);
    assertThat(body.path("text").asString()).isEqualTo("纯文本 <b>不解析</b> 😊");
    assertThat(body.has("parse_mode")).isFalse();
    assertThat(body.has("entities")).isFalse();
    assertThat(body.has("link_preview_options")).isFalse();
  }

  @ParameterizedTest
  @MethodSource("permanentStatuses")
  void mapsPermanentHttpStatusesWithoutLeakingRemoteData(int status) {
    SERVER.respondToPoll(
        status,
        "{\"ok\":false,\"description\":\"" + REMOTE_SECRET + "\",\"body\":\"secret-body\"}");

    assertFailure(
        () -> client().getUpdates(0, Duration.ofSeconds(1)),
        TelegramApiException.Reason.UNAUTHORIZED);
  }

  @ParameterizedTest
  @MethodSource("unavailableStatuses")
  void mapsServerFailuresWithoutParsingOrLeakingBodies(int status) {
    SERVER.respondToPoll(status, REMOTE_SECRET + " not-json");

    assertFailure(
        () -> client().getUpdates(0, Duration.ofSeconds(1)),
        TelegramApiException.Reason.UNAVAILABLE);
  }

  @Test
  void exposesOnlyBoundedRetryAfterForRateLimitsAndDoesNotRetry() {
    SERVER.respondToSend(
        429,
        "{\"ok\":false,\"error_code\":429,\"description\":\""
            + REMOTE_SECRET
            + "\",\"parameters\":{\"retry_after\":3}}");

    TelegramApiException failure =
        assertFailure(
            () -> client().sendMessage(10001, "只发送一次"), TelegramApiException.Reason.RATE_LIMITED);

    assertThat(failure.retryAfter()).contains(Duration.ofSeconds(3));
    assertThat(SERVER.requests()).hasSize(1);

    SERVER.reset();
    SERVER.respondToPoll(
        429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":11}}");
    TelegramApiException overBudget =
        assertFailure(
            () -> client().getUpdates(0, Duration.ofSeconds(1)),
            TelegramApiException.Reason.RATE_LIMITED);
    assertThat(overBudget.retryAfter()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("apiFailures")
  void mapsOkFalseUsingOnlyStableErrorCodes(int errorCode, TelegramApiException.Reason reason) {
    SERVER.respondToPoll(
        200,
        "{\"ok\":false,\"error_code\":"
            + errorCode
            + ",\"description\":\""
            + REMOTE_SECRET
            + "\"}");

    assertFailure(() -> client().getUpdates(0, Duration.ofSeconds(1)), reason);
  }

  @Test
  void rejectsMalformedShapesAndInvalidNumericProjection() {
    for (String invalid :
        List.of(
            "not-json " + REMOTE_SECRET,
            "{}",
            "{\"ok\":true,\"result\":{}}",
            "{\"ok\":true,\"result\":[{\"update_id\":\"501\"}]}",
            "{\"ok\":true,\"result\":[{\"update_id\":501,\"message\":{\"message_id\":1.5}}]}")) {
      SERVER.respondToPoll(200, invalid);
      assertFailure(
          () -> client().getUpdates(0, Duration.ofSeconds(1)),
          TelegramApiException.Reason.INVALID_RESPONSE);
    }
  }

  @Test
  void rejectsPollAndSendBodiesAboveFixedByteLimits() {
    SERVER.respondToPoll(200, " ".repeat(JdkTelegramBotApi.MAX_POLL_RESPONSE_BYTES + 1));
    assertFailure(
        () -> client().getUpdates(0, Duration.ofSeconds(1)),
        TelegramApiException.Reason.INVALID_RESPONSE);

    SERVER.reset();
    SERVER.respondToSend(200, " ".repeat(JdkTelegramBotApi.MAX_SEND_RESPONSE_BYTES + 1));
    assertFailure(
        () -> client().sendMessage(10001, "bounded"), TelegramApiException.Reason.INVALID_RESPONSE);
  }

  @Test
  @Tag("failure")
  void mapsRequestTimeoutWithoutRetainingHttpCause() {
    SERVER.respondToPollAfter(Duration.ofSeconds(2), 200, "{\"ok\":true,\"result\":[]}");
    JdkTelegramBotApi client =
        client(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1));

    assertFailure(
        () -> client.getUpdates(0, Duration.ofMillis(50)), TelegramApiException.Reason.TIMEOUT);
  }

  @Test
  @Tag("failure")
  void keepsTheRequestDeadlineWhileReadingAStalledBody() {
    SERVER.respondToPollBodyAfter(Duration.ofSeconds(2), 200, "{\"ok\":true,\"result\":[]}");
    JdkTelegramBotApi client =
        client(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1));

    assertFailure(
        () -> client.getUpdates(0, Duration.ofMillis(50)), TelegramApiException.Reason.TIMEOUT);
  }

  @Test
  @Tag("failure")
  void restoresInterruptAndMapsItWithoutRetainingTokenUri() throws Exception {
    SERVER.respondToPollAfter(Duration.ofSeconds(5), 200, "{\"ok\":true,\"result\":[]}");
    var failure = new AtomicReference<TelegramApiException>();
    var interruptRestored = new AtomicBoolean();
    Thread caller =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    client(Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(1))
                        .getUpdates(0, Duration.ofSeconds(1));
                  } catch (TelegramApiException exception) {
                    failure.set(exception);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                  }
                });

    caller.start();
    assertThat(SERVER.awaitRequest(Duration.ofSeconds(1))).isTrue();
    caller.interrupt();
    caller.join(Duration.ofSeconds(2));

    assertThat(caller.isAlive()).isFalse();
    assertThat(failure.get()).isNotNull();
    assertThat(failure.get().reason()).isEqualTo(TelegramApiException.Reason.INTERRUPTED);
    assertThat(failure.get()).hasNoCause();
    assertThat(failure.get().toString())
        .doesNotContain(FAKE_TOKEN, REMOTE_SECRET, SERVER.baseUri().toString());
    assertThat(interruptRestored).isTrue();
  }

  @Test
  void validatesLocalRequestBoundsBeforeNetworkAccess() {
    TelegramBotApi api = client();

    assertThatThrownBy(() -> api.getUpdates(-1, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.getUpdates(0, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.getUpdates(0, Duration.ofSeconds(26)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.sendMessage(0, "text"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.sendMessage(10001, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.sendMessage(10001, "x".repeat(4097)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SERVER.requests()).isEmpty();
  }

  private static TelegramApiException assertFailure(
      ThrowingCallable action, TelegramApiException.Reason expected) {
    TelegramApiException failure = catchThrowableOfType(action, TelegramApiException.class);
    assertThat(failure).isNotNull();
    assertThat(failure.reason()).isEqualTo(expected);
    assertThat(failure).hasNoCause();
    assertThat(failure.toString())
        .doesNotContain(FAKE_TOKEN, REMOTE_SECRET, "secret-body", SERVER.baseUri().toString());
    return failure;
  }

  private static JdkTelegramBotApi client() {
    return client(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1));
  }

  private static JdkTelegramBotApi client(
      Duration connectTimeout, Duration pollRequestTimeout, Duration sendRequestTimeout) {
    return new JdkTelegramBotApi(
        SERVER.baseUri(),
        new TelegramBotToken(FAKE_TOKEN),
        connectTimeout,
        pollRequestTimeout,
        sendRequestTimeout);
  }

  private static Stream<Integer> permanentStatuses() {
    return Stream.of(401, 403, 404);
  }

  private static Stream<Integer> unavailableStatuses() {
    return Stream.of(500, 502, 503);
  }

  private static Stream<Arguments> apiFailures() {
    return Stream.of(
        Arguments.of(401, TelegramApiException.Reason.UNAUTHORIZED),
        Arguments.of(429, TelegramApiException.Reason.RATE_LIMITED),
        Arguments.of(500, TelegramApiException.Reason.UNAVAILABLE),
        Arguments.of(400, TelegramApiException.Reason.INVALID_RESPONSE));
  }

  private static TelegramBotApiStubServer createServer() {
    try {
      return new TelegramBotApiStubServer();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
