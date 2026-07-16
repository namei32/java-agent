package io.namei.agent.bootstrap.channel.reliability;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.channels.reliability")
public final class ChannelReliabilityProperties {
  public static final int MAX_BATCH_SIZE = 1_000;
  public static final Duration MIN_RETENTION = Duration.ofDays(1);
  public static final Duration MAX_RETENTION = Duration.ofDays(365);
  public static final int MIN_INBOX_RECORDS = 1_000;
  public static final int MAX_INBOX_RECORDS = 1_000_000;
  public static final int MIN_DELIVERY_RECORDS = 100;
  public static final int MAX_DELIVERY_RECORDS = 100_000;

  private final ChannelReliabilityMode mode;
  private final int recoveryBatchSize;
  private final int cleanupBatchSize;
  private final Duration retention;
  private final int maxInboxRecords;
  private final int maxDeliveryRecords;

  @ConstructorBinding
  public ChannelReliabilityProperties(
      @DefaultValue("DISABLED") String mode,
      @DefaultValue("100") int recoveryBatchSize,
      @DefaultValue("100") int cleanupBatchSize,
      @DefaultValue("30d") Duration retention,
      @DefaultValue("100000") int maxInboxRecords,
      @DefaultValue("10000") int maxDeliveryRecords) {
    this(
        parseMode(mode),
        recoveryBatchSize,
        cleanupBatchSize,
        retention,
        maxInboxRecords,
        maxDeliveryRecords);
  }

  public ChannelReliabilityProperties(
      ChannelReliabilityMode mode,
      int recoveryBatchSize,
      int cleanupBatchSize,
      Duration retention,
      int maxInboxRecords,
      int maxDeliveryRecords) {
    this.mode = Objects.requireNonNull(mode, "agent.channels.reliability.mode");
    requireRange(recoveryBatchSize, 1, MAX_BATCH_SIZE, "recovery-batch-size");
    requireRange(cleanupBatchSize, 1, MAX_BATCH_SIZE, "cleanup-batch-size");
    requireDuration(retention);
    requireRange(maxInboxRecords, MIN_INBOX_RECORDS, MAX_INBOX_RECORDS, "max-inbox-records");
    requireRange(
        maxDeliveryRecords, MIN_DELIVERY_RECORDS, MAX_DELIVERY_RECORDS, "max-delivery-records");
    this.recoveryBatchSize = recoveryBatchSize;
    this.cleanupBatchSize = cleanupBatchSize;
    this.retention = retention;
    this.maxInboxRecords = maxInboxRecords;
    this.maxDeliveryRecords = maxDeliveryRecords;
  }

  public ChannelReliabilityMode mode() {
    return mode;
  }

  public int recoveryBatchSize() {
    return recoveryBatchSize;
  }

  public int cleanupBatchSize() {
    return cleanupBatchSize;
  }

  public Duration retention() {
    return retention;
  }

  public int maxInboxRecords() {
    return maxInboxRecords;
  }

  public int maxDeliveryRecords() {
    return maxDeliveryRecords;
  }

  @Override
  public String toString() {
    return "ChannelReliabilityProperties[mode=" + mode + ", budgets=<configured>]";
  }

  private static ChannelReliabilityMode parseMode(String value) {
    if (value == null) {
      throw new NullPointerException("agent.channels.reliability.mode");
    }
    return switch (value) {
      case "DISABLED" -> ChannelReliabilityMode.DISABLED;
      case "SQLITE" -> ChannelReliabilityMode.SQLITE;
      default -> throw new IllegalArgumentException("agent.channels.reliability.mode 无效");
    };
  }

  private static void requireDuration(Duration value) {
    if (value == null || value.compareTo(MIN_RETENTION) < 0 || value.compareTo(MAX_RETENTION) > 0) {
      throw new IllegalArgumentException("agent.channels.reliability.retention 必须在 1d..365d");
    }
  }

  private static void requireRange(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          "agent.channels.reliability." + field + " 必须在 " + minimum + ".." + maximum + " 之间");
    }
  }
}
