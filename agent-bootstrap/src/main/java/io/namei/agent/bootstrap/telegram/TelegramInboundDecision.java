package io.namei.agent.bootstrap.telegram;

import io.namei.agent.kernel.channel.InboundMessage;
import java.util.Objects;

public final class TelegramInboundDecision {
  public enum Kind {
    ACCEPTED,
    CONTROL,
    IGNORED
  }

  public enum Control {
    CANCEL
  }

  public enum IgnoreReason {
    UNSUPPORTED_UPDATE,
    NOT_PRIVATE,
    BOT_SENDER,
    INVALID_ID,
    IDENTITY_MISMATCH,
    NOT_ALLOWED,
    INVALID_TIME,
    UNSUPPORTED_CONTENT,
    BLANK_TEXT,
    CONTENT_TOO_LONG
  }

  private final Kind kind;
  private final InboundMessage inbound;
  private final Control control;
  private final IgnoreReason reason;

  private TelegramInboundDecision(
      Kind kind, InboundMessage inbound, Control control, IgnoreReason reason) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.inbound = inbound;
    this.control = control;
    this.reason = reason;
  }

  static TelegramInboundDecision accepted(InboundMessage inbound) {
    return new TelegramInboundDecision(
        Kind.ACCEPTED, Objects.requireNonNull(inbound, "inbound"), null, null);
  }

  static TelegramInboundDecision control(Control control) {
    return new TelegramInboundDecision(
        Kind.CONTROL, null, Objects.requireNonNull(control, "control"), null);
  }

  static TelegramInboundDecision ignored(IgnoreReason reason) {
    return new TelegramInboundDecision(
        Kind.IGNORED, null, null, Objects.requireNonNull(reason, "reason"));
  }

  public Kind kind() {
    return kind;
  }

  public InboundMessage inbound() {
    return inbound;
  }

  public Control control() {
    return control;
  }

  public IgnoreReason reason() {
    return reason;
  }

  @Override
  public String toString() {
    return switch (kind) {
      case ACCEPTED -> "TelegramInboundDecision[kind=ACCEPTED]";
      case CONTROL -> "TelegramInboundDecision[kind=CONTROL, control=" + control + "]";
      case IGNORED -> "TelegramInboundDecision[kind=IGNORED, reason=" + reason + "]";
    };
  }
}
