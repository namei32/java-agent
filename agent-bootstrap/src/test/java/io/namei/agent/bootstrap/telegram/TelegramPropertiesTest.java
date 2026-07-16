package io.namei.agent.bootstrap.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TelegramPropertiesTest {
  @Test
  void bindsSafeDisabledDefaultsWithoutAnAllowlist() {
    TelegramProperties properties =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("agent.channels.telegram", Bindable.of(TelegramProperties.class));

    assertThat(properties.enabled()).isFalse();
    assertThat(properties.allowedUserIds()).isEmpty();
    assertThat(properties.maxConcurrentTurns()).isEqualTo(8);
    assertThat(properties.bufferCapacity()).isEqualTo(32);
    assertThat(properties.publishTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(properties.pollTimeout()).isEqualTo(Duration.ofMillis(100));
    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(properties.longPollTimeout()).isEqualTo(Duration.ofSeconds(20));
    assertThat(properties.pollRequestTimeout()).isEqualTo(Duration.ofSeconds(25));
    assertThat(properties.sendRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(properties.retryBackoff()).isEqualTo(Duration.ofMillis(250));
    assertThat(properties.maxRetryAfter()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void enabledModeRequiresAUniquePositiveDecimalAllowlist() {
    assertThatThrownBy(() -> properties(true, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("agent.channels.telegram.allow-from 启用时不能为空");

    TelegramProperties properties =
        properties(true, List.of("10001", "10001", "9223372036854775807"));

    assertThat(properties.allowedUserIds()).containsExactly(10001L, Long.MAX_VALUE);
    assertThat(properties.allowFrom()).containsExactly("10001", "9223372036854775807");
    assertThat(properties.toString()).doesNotContain("10001", "9223372036854775807");
  }

  @Test
  void rejectsMutableUsernamesSignsZeroWhitespaceAndOverflow() {
    for (String invalid :
        List.of("namei", "@namei", "+1", "-1", "0", " 1", "1 ", "01", "9223372036854775808")) {
      assertThatThrownBy(() -> properties(true, List.of(invalid)))
          .as(invalid)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("agent.channels.telegram.allow-from 必须是正十进制 User ID");
    }
  }

  @Test
  void enforcesConcurrencyBufferAndEveryDurationBudget() {
    assertInvalid(() -> withMaxConcurrentTurns(0), "max-concurrent-turns");
    assertInvalid(() -> withMaxConcurrentTurns(33), "max-concurrent-turns");
    assertInvalid(() -> withBufferCapacity(0), "buffer-capacity");
    assertInvalid(() -> withBufferCapacity(1025), "buffer-capacity");
    assertInvalid(() -> withPublishTimeout(Duration.ZERO), "publish-timeout");
    assertInvalid(() -> withPublishTimeout(Duration.ofSeconds(31)), "publish-timeout");
    assertInvalid(() -> withPollTimeout(Duration.ofSeconds(31)), "poll-timeout");
    assertInvalid(() -> withConnectTimeout(Duration.ofSeconds(11)), "connect-timeout");
    assertInvalid(() -> withLongPollTimeout(Duration.ofSeconds(26)), "long-poll-timeout");
    assertInvalid(() -> withPollRequestTimeout(Duration.ofSeconds(31)), "poll-request-timeout");
    assertInvalid(() -> withSendRequestTimeout(Duration.ofSeconds(11)), "send-request-timeout");
    assertInvalid(() -> withShutdownTimeout(Duration.ofSeconds(31)), "shutdown-timeout");
    assertInvalid(() -> withRetryBackoff(Duration.ofMillis(2001)), "retry-backoff");
    assertInvalid(() -> withMaxRetryAfter(Duration.ofSeconds(11)), "max-retry-after");
  }

  @Test
  void requiresPollRequestDeadlineToExceedLongPollTimeout() {
    assertThatThrownBy(
            () ->
                create(
                    true,
                    List.of("10001"),
                    8,
                    32,
                    Duration.ofSeconds(2),
                    Duration.ofMillis(100),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5),
                    Duration.ofMillis(250),
                    Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("agent.channels.telegram.poll-request-timeout 必须大于 long-poll-timeout");
  }

  private static TelegramProperties properties(boolean enabled, List<String> allowFrom) {
    return create(
        enabled,
        allowFrom,
        8,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withMaxConcurrentTurns(int value) {
    return createUnchecked(
        value,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withBufferCapacity(int value) {
    return createUnchecked(
        8,
        value,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withPublishTimeout(Duration value) {
    return createUnchecked(
        8,
        32,
        value,
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withPollTimeout(Duration value) {
    return createUnchecked(
        8,
        32,
        Duration.ofSeconds(2),
        value,
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withConnectTimeout(Duration value) {
    return createUnchecked(
        8,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        value,
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withLongPollTimeout(Duration value) {
    return createUnchecked(
        8,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        value,
        Duration.ofSeconds(27),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withPollRequestTimeout(Duration value) {
    return createUnchecked(
        8,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        value,
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withSendRequestTimeout(Duration value) {
    return createUnchecked(
        8,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        value,
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withShutdownTimeout(Duration value) {
    return createUnchecked(
        8,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        value,
        Duration.ofMillis(250),
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withRetryBackoff(Duration value) {
    return createUnchecked(
        8,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        value,
        Duration.ofSeconds(5));
  }

  private static TelegramProperties withMaxRetryAfter(Duration value) {
    return createUnchecked(
        8,
        32,
        Duration.ofSeconds(2),
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        Duration.ofSeconds(25),
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMillis(250),
        value);
  }

  private static TelegramProperties createUnchecked(
      int maxConcurrentTurns,
      int bufferCapacity,
      Duration publishTimeout,
      Duration pollTimeout,
      Duration connectTimeout,
      Duration longPollTimeout,
      Duration pollRequestTimeout,
      Duration sendRequestTimeout,
      Duration shutdownTimeout,
      Duration retryBackoff,
      Duration maxRetryAfter) {
    return create(
        true,
        List.of("10001"),
        maxConcurrentTurns,
        bufferCapacity,
        publishTimeout,
        pollTimeout,
        connectTimeout,
        longPollTimeout,
        pollRequestTimeout,
        sendRequestTimeout,
        shutdownTimeout,
        retryBackoff,
        maxRetryAfter);
  }

  private static TelegramProperties create(
      boolean enabled,
      List<String> allowFrom,
      int maxConcurrentTurns,
      int bufferCapacity,
      Duration publishTimeout,
      Duration pollTimeout,
      Duration connectTimeout,
      Duration longPollTimeout,
      Duration pollRequestTimeout,
      Duration sendRequestTimeout,
      Duration shutdownTimeout,
      Duration retryBackoff,
      Duration maxRetryAfter) {
    return new TelegramProperties(
        enabled,
        allowFrom,
        maxConcurrentTurns,
        bufferCapacity,
        publishTimeout,
        pollTimeout,
        connectTimeout,
        longPollTimeout,
        pollRequestTimeout,
        sendRequestTimeout,
        shutdownTimeout,
        retryBackoff,
        maxRetryAfter);
  }

  private static void assertInvalid(ThrowingCallable action, String field) {
    assertThatThrownBy(action)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("agent.channels.telegram." + field);
  }
}
