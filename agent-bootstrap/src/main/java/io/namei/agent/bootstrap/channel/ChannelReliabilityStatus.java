package io.namei.agent.bootstrap.channel;

import java.util.Objects;
import java.util.regex.Pattern;

public record ChannelReliabilityStatus(
    Mode mode,
    LedgerState ledgerState,
    long pendingDeliveries,
    long unknownExecutions,
    long unknownDeliveries,
    String lastStableErrorCode) {
  private static final Pattern CODE = Pattern.compile("(?:|[A-Z][A-Z0-9_]{0,63})");

  public enum Mode {
    DISABLED,
    SQLITE
  }

  public enum LedgerState {
    DISABLED,
    NOT_STARTED,
    READY,
    FAILED
  }

  public ChannelReliabilityStatus {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(ledgerState, "ledgerState");
    if (mode == Mode.DISABLED && ledgerState != LedgerState.DISABLED) {
      throw new IllegalArgumentException("禁用可靠性时账本状态必须为 DISABLED");
    }
    if (mode == Mode.SQLITE && ledgerState == LedgerState.DISABLED) {
      throw new IllegalArgumentException("SQLite 可靠性必须暴露账本状态");
    }
    if (pendingDeliveries < 0 || unknownExecutions < 0 || unknownDeliveries < 0) {
      throw new IllegalArgumentException("渠道可靠性计数不能为负数");
    }
    if (lastStableErrorCode == null || !CODE.matcher(lastStableErrorCode).matches()) {
      throw new IllegalArgumentException("渠道可靠性错误码无效");
    }
  }

  public static ChannelReliabilityStatus disabled() {
    return new ChannelReliabilityStatus(Mode.DISABLED, LedgerState.DISABLED, 0, 0, 0, "");
  }
}
