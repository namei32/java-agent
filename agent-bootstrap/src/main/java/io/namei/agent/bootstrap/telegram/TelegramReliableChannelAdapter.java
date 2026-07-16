package io.namei.agent.bootstrap.telegram;

import io.namei.agent.application.ChannelDeliveryCoordinator;
import io.namei.agent.application.ChannelDeliverySettings;
import io.namei.agent.application.ChannelDeliveryWorker;
import io.namei.agent.application.ChannelDeliveryWorkerSettings;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.application.ReliableChannelException;
import io.namei.agent.application.ReliableInboundCoordinator;
import io.namei.agent.application.ReliableInboundEvent;
import io.namei.agent.application.ReliableInboundResult;
import io.namei.agent.application.ReliableInboundSettings;
import io.namei.agent.application.ReliableTurnStarter;
import io.namei.agent.bootstrap.channel.ChannelAdapter;
import io.namei.agent.bootstrap.channel.ChannelReliabilityStatus;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.bootstrap.channel.ChannelStatusSnapshot;
import io.namei.agent.bootstrap.channel.reliability.ChannelReliabilityRuntime;
import io.namei.agent.kernel.channel.InboundMessage;
import io.namei.agent.kernel.channel.reliability.ChannelInstanceId;
import io.namei.agent.kernel.port.ChannelLedgerPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public final class TelegramReliableChannelAdapter implements ChannelAdapter {
  private static final String NAME = "telegram";
  private static final String POLL_WORKER = "telegram-reliable-poll-worker";
  private static final String CHUNK_ALGORITHM = "telegram-text-chunks-v1";
  private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;
  private static final Duration TURN_LEASE = Duration.ofMinutes(5);
  private static final Duration DELIVERY_LEASE = Duration.ofSeconds(30);
  private static final Duration CURSOR_OBSERVATION_INTERVAL = Duration.ofMillis(2);

  private final TelegramBotApi api;
  private final TelegramUpdateMapper mapper;
  private final MessageTurnService turns;
  private final TelegramProperties properties;
  private final TelegramChannelInstance instance;
  private final ChannelReliabilityRuntime runtime;
  private final ChannelThreadStarter threadStarter;
  private final ChannelSleeper sleeper;
  private final TelegramTerminalRenderer terminalProjector =
      new TelegramTerminalRenderer(new TelegramTextChunker());
  private final AtomicReference<ChannelState> state = new AtomicReference<>(ChannelState.NEW);
  private final AtomicReference<String> code = new AtomicReference<>("");
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicBoolean accepting = new AtomicBoolean();
  private final Object lifecycle = new Object();

  private volatile long nextOffset;
  private volatile Thread pollThread;
  private volatile ChannelReliabilityRuntime.Session session;
  private volatile ChannelDeliveryWorker deliveryWorker;
  private volatile ReliableInboundCoordinator inbound;

  public TelegramReliableChannelAdapter(
      TelegramBotApi api,
      TelegramUpdateMapper mapper,
      MessageTurnService turns,
      TelegramProperties properties,
      TelegramChannelInstance instance,
      ChannelReliabilityRuntime runtime,
      ChannelThreadStarter threadStarter,
      ChannelSleeper sleeper) {
    this.api = Objects.requireNonNull(api, "api");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.instance = Objects.requireNonNull(instance, "instance");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.threadStarter = Objects.requireNonNull(threadStarter, "threadStarter");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public void start() {
    synchronized (lifecycle) {
      if (!state.compareAndSet(ChannelState.NEW, ChannelState.STARTING)) {
        throw new IllegalStateException("Telegram 可靠渠道不能重复启动");
      }
      try {
        startRuntime();
        ChannelState initialState =
            session.capacityAvailable() ? ChannelState.RUNNING : ChannelState.DEGRADED;
        if (!session.capacityAvailable()) {
          code.set("CHANNEL_LEDGER_CAPACITY_EXCEEDED");
        }
        state.set(initialState);
        accepting.set(true);
        pollThread =
            Objects.requireNonNull(
                threadStarter.start(POLL_WORKER, this::pollLoop), "threadStarter 返回了 null");
      } catch (Throwable failure) {
        accepting.set(false);
        if (code.get().isEmpty()) {
          code.set(startFailureCode(failure));
        }
        state.set(ChannelState.FAILED);
        closeStartedResources();
        throw new IllegalStateException("Telegram 可靠渠道启动失败");
      }
    }
  }

  private void startRuntime() {
    ChannelReliabilityRuntime.Session opened = runtime.start(instance.id());
    session = opened;
    ChannelLedgerPort ledger = opened.ledger();
    nextOffset = opened.snapshot().nextSequence();

    ReliableTurnStarter reliableStarter = threadStarter::start;
    var worker =
        new ChannelDeliveryWorker(
            new ChannelDeliveryCoordinator(
                ledger,
                new TelegramDeliveryTransport(api),
                runtime.clock(),
                instance.id(),
                opened.ownerId(),
                new ChannelDeliverySettings(DELIVERY_LEASE, properties.maxRetryAfter())),
            reliableStarter,
            runtime.clock(),
            new ChannelDeliveryWorkerSettings(
                runtime.properties().recoveryBatchSize(),
                properties.retryBackoff(),
                quarter(properties.shutdownTimeout())));
    deliveryWorker = worker;

    var secureIds = new SecureTelegramIdGenerator();
    inbound =
        new ReliableInboundCoordinator(
            ledger,
            (context, cancellation) -> {
              var durable =
                  new ReliableTelegramOutboundSink(
                      ledger,
                      context,
                      CHUNK_ALGORITHM,
                      terminalProjector,
                      worker,
                      runtime.clock(),
                      properties.bufferCapacity(),
                      properties.publishTimeout());
              turns.process(context.inbound(), durable, cancellation);
            },
            reliableStarter,
            runtime.clock(),
            opened::ownerId,
            secureIds::newTurnId,
            new ReliableInboundSettings(
                properties.maxConcurrentTurns(),
                TURN_LEASE,
                runtime.properties().recoveryBatchSize(),
                CHUNK_ALGORITHM,
                new TelegramTextChunker().split(TelegramChannelAdapter.SESSION_BUSY_TEXT),
                new TelegramTextChunker().split(TelegramChannelAdapter.NO_ACTIVE_TURN_TEXT)));
    try {
      worker.start();
    } catch (RuntimeException | Error failure) {
      code.set("DELIVERY_WORKER_START_FAILED");
      throw failure;
    }
  }

  @Override
  public void stopAccepting() {
    accepting.set(false);
    state.updateAndGet(
        current ->
            switch (current) {
              case NEW, STOPPED, FAILED -> current;
              default -> ChannelState.STOPPING;
            });
    interruptPoll();
  }

  @Override
  public ChannelStatusSnapshot snapshot() {
    ReliableInboundCoordinator currentInbound = inbound;
    return new ChannelStatusSnapshot(
        NAME,
        state.get(),
        code.get(),
        currentInbound == null ? 0 : currentInbound.activeTurnCount(),
        consecutiveFailures.get(),
        reliabilityStatus());
  }

  private ChannelReliabilityStatus reliabilityStatus() {
    ChannelReliabilityRuntime.Session current = session;
    if (current == null) {
      return new ChannelReliabilityStatus(
          ChannelReliabilityStatus.Mode.SQLITE,
          state.get() == ChannelState.FAILED
              ? ChannelReliabilityStatus.LedgerState.FAILED
              : ChannelReliabilityStatus.LedgerState.NOT_STARTED,
          0,
          0,
          0,
          reliableCode());
    }
    try {
      var ledger = current.ledger().snapshot(instance.id());
      return new ChannelReliabilityStatus(
          ChannelReliabilityStatus.Mode.SQLITE,
          ChannelReliabilityStatus.LedgerState.READY,
          ledger.pendingDeliveries(),
          ledger.unknownExecutions(),
          ledger.unknownDeliveries(),
          ledger.stableErrorCode().isEmpty() ? reliableCode() : ledger.stableErrorCode());
    } catch (RuntimeException failure) {
      return new ChannelReliabilityStatus(
          ChannelReliabilityStatus.Mode.SQLITE,
          ChannelReliabilityStatus.LedgerState.FAILED,
          0,
          0,
          0,
          "CHANNEL_LEDGER_UNAVAILABLE");
    }
  }

  private String reliableCode() {
    String current = code.get();
    return current.startsWith("CHANNEL_") ? current : "";
  }

  @Override
  public void close() {
    synchronized (lifecycle) {
      ChannelState current = state.get();
      if (current == ChannelState.STOPPED) {
        return;
      }
      if (current == ChannelState.NEW) {
        accepting.set(false);
        state.set(ChannelState.STOPPED);
        return;
      }
      accepting.set(false);
      state.set(ChannelState.STOPPING);
      interruptPoll();

      boolean stopped = joinPoll(quarter(properties.shutdownTimeout()));
      ReliableInboundCoordinator currentInbound = inbound;
      if (currentInbound != null) {
        stopped &= currentInbound.shutdown(half(properties.shutdownTimeout()));
      }
      ChannelDeliveryWorker currentWorker = deliveryWorker;
      if (currentWorker != null) {
        currentWorker.close();
        stopped &= !currentWorker.isRunning();
      }
      ChannelReliabilityRuntime.Session currentSession = session;
      if (stopped && currentSession != null) {
        currentSession.close();
      }

      if (!stopped) {
        code.set("SHUTDOWN_TIMEOUT");
        state.set(ChannelState.FAILED);
        throw new IllegalStateException("Telegram 可靠渠道未能在期限内停止");
      }
      code.set("");
      state.set(ChannelState.STOPPED);
    }
  }

  long nextOffset() {
    return nextOffset;
  }

  ChannelInstanceId instanceId() {
    return instance.id();
  }

  private void pollLoop() {
    while (accepting.get()) {
      try {
        refreshOffset();
        List<TelegramUpdate> updates =
            validatedUpdates(api.getUpdates(nextOffset, properties.longPollTimeout()));
        consecutiveFailures.set(0);
        code.set("");
        state.set(ChannelState.RUNNING);
        for (TelegramUpdate update : updates) {
          if (!accepting.get()) {
            return;
          }
          processUpdate(update);
        }
      } catch (TelegramApiException failure) {
        if (!accepting.get() && failure.reason() == TelegramApiException.Reason.INTERRUPTED) {
          return;
        }
        if (!handlePollFailure(failure)) {
          return;
        }
      } catch (ReliableChannelException failure) {
        consecutiveFailures.incrementAndGet();
        failPermanently(failure.failure().code());
        return;
      } catch (InterruptedRuntimeException interrupted) {
        if (!accepting.get()) {
          return;
        }
        consecutiveFailures.incrementAndGet();
        failPermanently("POLL_INTERRUPTED");
        return;
      } catch (RuntimeException failure) {
        if (!accepting.get()) {
          return;
        }
        consecutiveFailures.incrementAndGet();
        failPermanently("POLL_INVALID_RESPONSE");
        return;
      }
    }
  }

  private void processUpdate(TelegramUpdate update) {
    TelegramInboundDecision decision = mapper.map(update);
    long sequence = update.updateId();
    String eventId = Long.toString(sequence);
    ReliableInboundEvent event =
        switch (decision.kind()) {
          case IGNORED ->
              ReliableInboundEvent.ignored(
                  instance.id(), eventId, sequence, decision.reason().name());
          case CONTROL -> {
            TelegramMessage message = Objects.requireNonNull(update.message(), "control message");
            String targetId = Long.toString(message.chatId());
            yield ReliableInboundEvent.control(
                instance.id(),
                eventId,
                sequence,
                decision.control().name(),
                targetId,
                "telegram:" + targetId);
          }
          case ACCEPTED -> {
            InboundMessage inboundMessage = Objects.requireNonNull(decision.inbound(), "inbound");
            yield ReliableInboundEvent.accepted(
                instance.id(),
                eventId,
                sequence,
                inboundMessage.route().conversationId(),
                inboundMessage);
          }
        };
    ReliableInboundResult result = Objects.requireNonNull(inbound.handle(event), "inbound result");
    if (result.status() == ReliableInboundResult.Status.FEEDBACK_QUEUED) {
      deliveryWorker.signal();
    }
    nextOffset = Math.max(nextOffset, result.nextSequence());
    if (nextOffset <= sequence && requiresTurnBoundary(result.status())) {
      awaitDurableCursor(sequence);
    }
  }

  private void awaitDurableCursor(long sequence) {
    long deadline = saturatedAdd(System.nanoTime(), properties.shutdownTimeout().toNanos());
    while (accepting.get() && nextOffset <= sequence) {
      refreshOffset();
      if (nextOffset > sequence) {
        return;
      }
      if (System.nanoTime() >= deadline) {
        throw new ReliableChannelException(
            io.namei.agent.application.ReliableChannelFailure.TURN_START_FAILED);
      }
      LockSupport.parkNanos(CURSOR_OBSERVATION_INTERVAL.toNanos());
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new InterruptedRuntimeException();
      }
    }
  }

  private void refreshOffset() {
    ChannelReliabilityRuntime.Session current = Objects.requireNonNull(session, "session");
    nextOffset = Math.max(nextOffset, current.ledger().snapshot(instance.id()).nextSequence());
  }

  private static List<TelegramUpdate> validatedUpdates(List<TelegramUpdate> response) {
    if (response == null) {
      throw new IllegalArgumentException("Telegram Poll 响应为空");
    }
    var updates = new ArrayList<TelegramUpdate>(response.size());
    for (TelegramUpdate update : response) {
      if (update == null || update.updateId() < 0 || update.updateId() == Long.MAX_VALUE) {
        throw new IllegalArgumentException("Telegram Update ID 无效");
      }
      updates.add(update);
    }
    updates.sort(Comparator.comparingLong(TelegramUpdate::updateId));
    return List.copyOf(updates);
  }

  private boolean handlePollFailure(TelegramApiException failure) {
    int failures = consecutiveFailures.incrementAndGet();
    if (!retryable(failure.reason())) {
      failPermanently(permanentPollCode(failure.reason()));
      return false;
    }
    if (failures >= MAX_CONSECUTIVE_POLL_FAILURES) {
      failPermanently("POLL_RETRY_EXHAUSTED");
      return false;
    }
    code.set("POLL_" + failure.reason().name());
    state.set(ChannelState.DEGRADED);
    try {
      sleeper.sleep(properties.retryBackoff());
      return accepting.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      if (!accepting.get()) {
        return false;
      }
      failPermanently("POLL_INTERRUPTED");
      return false;
    }
  }

  private void failPermanently(String stableCode) {
    accepting.set(false);
    code.set(stableCode);
    state.set(ChannelState.FAILED);
  }

  private void interruptPoll() {
    Thread current = pollThread;
    if (current != null && current != Thread.currentThread()) {
      current.interrupt();
    }
  }

  private boolean joinPoll(Duration timeout) {
    Thread current = pollThread;
    if (current == null || current == Thread.currentThread() || !current.isAlive()) {
      return true;
    }
    try {
      return current.join(timeout);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void closeStartedResources() {
    interruptPoll();
    ChannelDeliveryWorker worker = deliveryWorker;
    if (worker != null) {
      worker.close();
    }
    ChannelReliabilityRuntime.Session current = session;
    if ((worker == null || !worker.isRunning()) && current != null) {
      current.close();
    }
  }

  private static String startFailureCode(Throwable failure) {
    if (failure instanceof ReliableChannelException reliable) {
      return reliable.failure().code();
    }
    return "CHANNEL_LEDGER_UNAVAILABLE";
  }

  private static boolean requiresTurnBoundary(ReliableInboundResult.Status status) {
    return status == ReliableInboundResult.Status.TURN_SCHEDULED
        || status == ReliableInboundResult.Status.IN_PROGRESS;
  }

  private static boolean retryable(TelegramApiException.Reason reason) {
    return reason == TelegramApiException.Reason.TIMEOUT
        || reason == TelegramApiException.Reason.UNAVAILABLE
        || reason == TelegramApiException.Reason.RATE_LIMITED;
  }

  private static String permanentPollCode(TelegramApiException.Reason reason) {
    return switch (reason) {
      case UNAUTHORIZED -> "POLL_UNAUTHORIZED";
      case PERMANENT_REJECTION, INVALID_RESPONSE -> "POLL_INVALID_RESPONSE";
      case INTERRUPTED -> "POLL_INTERRUPTED";
      case RATE_LIMITED, TIMEOUT, UNAVAILABLE -> "POLL_RETRY_EXHAUSTED";
    };
  }

  private static Duration half(Duration duration) {
    return Duration.ofNanos(Math.max(1, duration.toNanos() / 2));
  }

  private static Duration quarter(Duration duration) {
    return Duration.ofNanos(Math.max(1, duration.toNanos() / 4));
  }

  private static long saturatedAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static final class InterruptedRuntimeException extends RuntimeException {
    private InterruptedRuntimeException() {
      super("Telegram Poll 被中断", null, false, false);
    }
  }
}
