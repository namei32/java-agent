package io.namei.agent.bootstrap.telegram;

import io.namei.agent.application.BoundedOutboundBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.channels.telegram")
public final class TelegramProperties {
  public static final int MAX_CONCURRENT_TURNS = 32;
  public static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  public static final Duration MAX_LONG_POLL_TIMEOUT = Duration.ofSeconds(25);
  public static final Duration MAX_POLL_REQUEST_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration MAX_SEND_REQUEST_TIMEOUT = Duration.ofSeconds(10);
  public static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(2);
  public static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");

  private final boolean enabled;
  private final List<String> allowFrom;
  private final Set<Long> allowedUserIds;
  private final int maxConcurrentTurns;
  private final int bufferCapacity;
  private final Duration publishTimeout;
  private final Duration pollTimeout;
  private final Duration connectTimeout;
  private final Duration longPollTimeout;
  private final Duration pollRequestTimeout;
  private final Duration sendRequestTimeout;
  private final Duration shutdownTimeout;
  private final Duration retryBackoff;
  private final Duration maxRetryAfter;

  public TelegramProperties(
      @DefaultValue("false") boolean enabled,
      List<String> allowFrom,
      @DefaultValue("8") int maxConcurrentTurns,
      @DefaultValue("32") int bufferCapacity,
      @DefaultValue("2s") Duration publishTimeout,
      @DefaultValue("100ms") Duration pollTimeout,
      @DefaultValue("5s") Duration connectTimeout,
      @DefaultValue("20s") Duration longPollTimeout,
      @DefaultValue("25s") Duration pollRequestTimeout,
      @DefaultValue("5s") Duration sendRequestTimeout,
      @DefaultValue("5s") Duration shutdownTimeout,
      @DefaultValue("250ms") Duration retryBackoff,
      @DefaultValue("5s") Duration maxRetryAfter) {
    Allowlist normalizedAllowlist = normalizeAllowlist(allowFrom);
    if (enabled && normalizedAllowlist.ids().isEmpty()) {
      throw new IllegalArgumentException("agent.channels.telegram.allow-from 启用时不能为空");
    }
    requireRange(
        maxConcurrentTurns,
        1,
        MAX_CONCURRENT_TURNS,
        "agent.channels.telegram.max-concurrent-turns");
    requireRange(
        bufferCapacity,
        1,
        BoundedOutboundBuffer.MAX_CAPACITY,
        "agent.channels.telegram.buffer-capacity");
    requirePositiveBounded(
        publishTimeout, BoundedOutboundBuffer.MAX_WAIT, "agent.channels.telegram.publish-timeout");
    requirePositiveBounded(
        pollTimeout, BoundedOutboundBuffer.MAX_WAIT, "agent.channels.telegram.poll-timeout");
    requirePositiveBounded(
        connectTimeout, MAX_CONNECT_TIMEOUT, "agent.channels.telegram.connect-timeout");
    requirePositiveBounded(
        longPollTimeout, MAX_LONG_POLL_TIMEOUT, "agent.channels.telegram.long-poll-timeout");
    requirePositiveBounded(
        pollRequestTimeout,
        MAX_POLL_REQUEST_TIMEOUT,
        "agent.channels.telegram.poll-request-timeout");
    requirePositiveBounded(
        sendRequestTimeout,
        MAX_SEND_REQUEST_TIMEOUT,
        "agent.channels.telegram.send-request-timeout");
    requirePositiveBounded(
        shutdownTimeout,
        BoundedOutboundBuffer.MAX_WAIT,
        "agent.channels.telegram.shutdown-timeout");
    requirePositiveBounded(
        retryBackoff, MAX_RETRY_BACKOFF, "agent.channels.telegram.retry-backoff");
    requirePositiveBounded(
        maxRetryAfter, MAX_RETRY_AFTER, "agent.channels.telegram.max-retry-after");
    if (longPollTimeout.compareTo(pollRequestTimeout) >= 0) {
      throw new IllegalArgumentException(
          "agent.channels.telegram.poll-request-timeout 必须大于 long-poll-timeout");
    }

    this.enabled = enabled;
    this.allowFrom = normalizedAllowlist.values();
    this.allowedUserIds = normalizedAllowlist.ids();
    this.maxConcurrentTurns = maxConcurrentTurns;
    this.bufferCapacity = bufferCapacity;
    this.publishTimeout = publishTimeout;
    this.pollTimeout = pollTimeout;
    this.connectTimeout = connectTimeout;
    this.longPollTimeout = longPollTimeout;
    this.pollRequestTimeout = pollRequestTimeout;
    this.sendRequestTimeout = sendRequestTimeout;
    this.shutdownTimeout = shutdownTimeout;
    this.retryBackoff = retryBackoff;
    this.maxRetryAfter = maxRetryAfter;
  }

  public boolean enabled() {
    return enabled;
  }

  public List<String> allowFrom() {
    return allowFrom;
  }

  public Set<Long> allowedUserIds() {
    return allowedUserIds;
  }

  public int maxConcurrentTurns() {
    return maxConcurrentTurns;
  }

  public int bufferCapacity() {
    return bufferCapacity;
  }

  public Duration publishTimeout() {
    return publishTimeout;
  }

  public Duration pollTimeout() {
    return pollTimeout;
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration longPollTimeout() {
    return longPollTimeout;
  }

  public Duration pollRequestTimeout() {
    return pollRequestTimeout;
  }

  public Duration sendRequestTimeout() {
    return sendRequestTimeout;
  }

  public Duration shutdownTimeout() {
    return shutdownTimeout;
  }

  public Duration retryBackoff() {
    return retryBackoff;
  }

  public Duration maxRetryAfter() {
    return maxRetryAfter;
  }

  @Override
  public String toString() {
    return "TelegramProperties[enabled="
        + enabled
        + ", allowFrom=<redacted>, budgets=<configured>]";
  }

  private static Allowlist normalizeAllowlist(List<String> configured) {
    var values = new ArrayList<String>();
    var ids = new LinkedHashSet<Long>();
    for (String raw : configured == null ? List.<String>of() : configured) {
      if (raw == null || !POSITIVE_DECIMAL.matcher(raw).matches()) {
        throw invalidAllowlist();
      }
      long id;
      try {
        id = Long.parseLong(raw);
      } catch (NumberFormatException invalid) {
        throw invalidAllowlist();
      }
      if (ids.add(id)) {
        values.add(raw);
      }
    }
    return new Allowlist(
        List.copyOf(values), Collections.unmodifiableSet(new LinkedHashSet<>(ids)));
  }

  private static IllegalArgumentException invalidAllowlist() {
    return new IllegalArgumentException("agent.channels.telegram.allow-from 必须是正十进制 User ID");
  }

  private static void requireRange(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " 必须在 " + minimum + ".." + maximum + " 之间");
    }
  }

  private static void requirePositiveBounded(Duration value, Duration maximum, String field) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " 必须为正数");
    }
    if (value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(field + " 超过上限");
    }
  }

  private record Allowlist(List<String> values, Set<Long> ids) {}
}
