package io.namei.agent.bootstrap.telegram;

import io.namei.agent.application.BoundedOutboundBuffer;
import io.namei.agent.application.MessageTurnService;
import io.namei.agent.bootstrap.channel.ChannelAdapter;
import io.namei.agent.bootstrap.channel.ChannelState;
import io.namei.agent.bootstrap.channel.ChannelStatusSnapshot;
import io.namei.agent.kernel.channel.InboundMessage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class TelegramChannelAdapter implements ChannelAdapter {
  public static final String SESSION_BUSY_TEXT = "当前会话正忙，请稍后重试（SESSION_BUSY）";
  public static final String NO_ACTIVE_TURN_TEXT = "当前没有可取消的请求";

  private static final String NAME = "telegram";
  private static final String POLL_WORKER = "telegram-poll-worker";
  private static final String TURN_WORKER = "telegram-turn-worker";
  private static final String TURN_PRODUCER = "telegram-turn-producer";
  private static final int SEEN_UPDATE_LIMIT = 1024;
  private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;

  private final TelegramBotApi api;
  private final TelegramUpdateMapper mapper;
  private final MessageTurnService turns;
  private final TelegramProperties properties;
  private final ChannelThreadStarter threadStarter;
  private final ChannelSleeper sleeper;
  private final TelegramDeliveryPolicy delivery;
  private final TelegramTextChunker chunker = new TelegramTextChunker();
  private final Semaphore turnPermits;
  private final ConcurrentHashMap<String, ActiveTelegramTurn> activeTurns =
      new ConcurrentHashMap<>();
  private final AtomicReference<ChannelState> state = new AtomicReference<>(ChannelState.NEW);
  private final AtomicReference<String> code = new AtomicReference<>("");
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private final AtomicBoolean accepting = new AtomicBoolean();
  private final Set<Long> seenUpdates = new HashSet<>();
  private final ArrayDeque<Long> seenOrder = new ArrayDeque<>();
  private final Object lifecycle = new Object();
  private final Object turnRegistration = new Object();

  private volatile long nextOffset;
  private volatile Thread pollThread;

  public TelegramChannelAdapter(
      TelegramBotApi api,
      TelegramUpdateMapper mapper,
      MessageTurnService turns,
      TelegramProperties properties,
      ChannelThreadStarter threadStarter,
      ChannelSleeper sleeper) {
    this.api = Objects.requireNonNull(api, "api");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.threadStarter = Objects.requireNonNull(threadStarter, "threadStarter");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    this.delivery = new TelegramDeliveryPolicy(api, this.sleeper, properties.maxRetryAfter());
    this.turnPermits = new Semaphore(properties.maxConcurrentTurns(), true);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public void start() {
    synchronized (lifecycle) {
      if (!state.compareAndSet(ChannelState.NEW, ChannelState.STARTING)) {
        throw new IllegalStateException("Telegram Channel 不能重复启动");
      }
      accepting.set(true);
      try {
        pollThread =
            Objects.requireNonNull(
                threadStarter.start(POLL_WORKER, this::pollLoop), "threadStarter 返回了 null");
      } catch (Throwable failure) {
        accepting.set(false);
        code.set("POLL_WORKER_START_FAILED");
        state.set(ChannelState.FAILED);
        throw new IllegalStateException("Telegram Channel 启动失败");
      }
      state.compareAndSet(ChannelState.STARTING, ChannelState.RUNNING);
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
    Thread currentPoll = pollThread;
    if (currentPoll != null && currentPoll != Thread.currentThread()) {
      currentPoll.interrupt();
    }
  }

  @Override
  public ChannelStatusSnapshot snapshot() {
    return new ChannelStatusSnapshot(
        NAME, state.get(), code.get(), activeTurns.size(), consecutiveFailures.get());
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

      Thread currentPoll = pollThread;
      if (currentPoll != null && currentPoll != Thread.currentThread()) {
        currentPoll.interrupt();
      }
      List<ActiveTelegramTurn> closingTurns;
      synchronized (turnRegistration) {
        closingTurns = List.copyOf(activeTurns.values());
      }
      closingTurns.forEach(turn -> turn.buffer().shutdown());

      long startedAt = System.nanoTime();
      long shutdownBudget = properties.shutdownTimeout().toNanos();
      long gracefulDeadline = startedAt + shutdownBudget / 2;
      long finalDeadline = startedAt + shutdownBudget;
      boolean stopped = joinUntil(currentPoll, gracefulDeadline);
      for (ActiveTelegramTurn turn : closingTurns) {
        stopped &= joinTurn(turn, gracefulDeadline);
      }
      if (!stopped) {
        if (currentPoll != null && currentPoll != Thread.currentThread()) {
          currentPoll.interrupt();
        }
        closingTurns.forEach(ActiveTelegramTurn::interruptWorkers);
        stopped = joinUntil(currentPoll, finalDeadline);
        for (ActiveTelegramTurn turn : closingTurns) {
          stopped &= joinTurn(turn, finalDeadline);
        }
      }
      closingTurns.stream().filter(ActiveTelegramTurn::workersStopped).forEach(this::cleanupTurn);

      if (!stopped
          || !activeTurns.isEmpty()
          || turnPermits.availablePermits() != properties.maxConcurrentTurns()) {
        code.set("SHUTDOWN_TIMEOUT");
        state.set(ChannelState.FAILED);
        throw new IllegalStateException("Telegram Channel 未能在期限内停止");
      }
      code.set("");
      state.set(ChannelState.STOPPED);
    }
  }

  long nextOffset() {
    return nextOffset;
  }

  int availableTurnPermits() {
    return turnPermits.availablePermits();
  }

  private void pollLoop() {
    while (accepting.get()) {
      try {
        List<TelegramUpdate> updates =
            Objects.requireNonNull(
                api.getUpdates(nextOffset, properties.longPollTimeout()), "Telegram API 返回了 null");
        consecutiveFailures.set(0);
        if (accepting.get()) {
          code.set("");
          state.set(ChannelState.RUNNING);
        }
        for (TelegramUpdate update : updates) {
          if (!accepting.get()) {
            return;
          }
          switch (processUpdate(update)) {
            case SAFE -> {
              // Continue this already bounded response batch.
            }
            case STOPPED -> {
              return;
            }
            case TURN_START_FAILED -> {
              failPermanently("TURN_WORKER_START_FAILED");
              return;
            }
            case INVALID -> {
              consecutiveFailures.incrementAndGet();
              failPermanently("POLL_INVALID_RESPONSE");
              return;
            }
          }
        }
      } catch (TelegramApiException failure) {
        if (!accepting.get() && failure.reason() == TelegramApiException.Reason.INTERRUPTED) {
          return;
        }
        if (!handlePollFailure(failure)) {
          return;
        }
      } catch (Throwable failure) {
        if (!accepting.get()) {
          return;
        }
        consecutiveFailures.incrementAndGet();
        failPermanently("POLL_INVALID_RESPONSE");
        return;
      }
    }
  }

  private UpdateResult processUpdate(TelegramUpdate update) {
    if (update == null) {
      return UpdateResult.INVALID;
    }
    long updateId = update.updateId();
    if (updateId < 0 || updateId == Long.MAX_VALUE) {
      return UpdateResult.INVALID;
    }
    if (updateId < nextOffset || seenUpdates.contains(updateId)) {
      return UpdateResult.SAFE;
    }

    TelegramInboundDecision decision = mapper.map(update);
    return switch (decision.kind()) {
      case IGNORED -> {
        markSafe(updateId);
        yield UpdateResult.SAFE;
      }
      case CONTROL -> {
        handleControl(update.message().chatId());
        markSafe(updateId);
        yield UpdateResult.SAFE;
      }
      case ACCEPTED -> {
        TurnStartResult started = startTurn(update.message().chatId(), decision.inbound());
        if (started == TurnStartResult.FAILED) {
          yield UpdateResult.TURN_START_FAILED;
        }
        if (started == TurnStartResult.STOPPED) {
          yield UpdateResult.STOPPED;
        }
        markSafe(updateId);
        yield UpdateResult.SAFE;
      }
    };
  }

  private void handleControl(long chatId) {
    ActiveTelegramTurn active = activeTurns.get("telegram:" + chatId);
    if (active == null || !active.buffer().requestCancellation()) {
      sendFixed(chatId, NO_ACTIVE_TURN_TEXT);
    }
  }

  private TurnStartResult startTurn(long chatId, InboundMessage inbound) {
    if (!accepting.get()) {
      return TurnStartResult.STOPPED;
    }
    if (!turnPermits.tryAcquire()) {
      sendFixed(chatId, SESSION_BUSY_TEXT);
      return TurnStartResult.BUSY;
    }

    var buffer =
        new BoundedOutboundBuffer(
            inbound, properties.bufferCapacity(), properties.publishTimeout());
    var active = new ActiveTelegramTurn(chatId, inbound, buffer);
    ActiveTelegramTurn existing;
    synchronized (turnRegistration) {
      if (!accepting.get()) {
        turnPermits.release();
        return TurnStartResult.STOPPED;
      }
      existing = activeTurns.putIfAbsent(inbound.sessionId(), active);
    }
    if (existing != null) {
      turnPermits.release();
      sendFixed(chatId, SESSION_BUSY_TEXT);
      return TurnStartResult.BUSY;
    }

    try {
      Thread worker =
          Objects.requireNonNull(
              threadStarter.start(TURN_WORKER, () -> runTurn(active)), "threadStarter 返回了 null");
      active.worker(worker);
    } catch (Throwable failure) {
      active.abortStartup();
      cleanupTurn(active);
      return TurnStartResult.FAILED;
    }

    if (!active.awaitStartup(properties.shutdownTimeout())) {
      cleanupTurn(active);
      return accepting.get() ? TurnStartResult.FAILED : TurnStartResult.STOPPED;
    }
    return TurnStartResult.STARTED;
  }

  private void runTurn(ActiveTelegramTurn active) {
    try {
      Thread producer;
      try {
        producer =
            Objects.requireNonNull(
                threadStarter.start(TURN_PRODUCER, () -> produce(active)),
                "threadStarter 返回了 null");
        active.producer(producer);
      } catch (Throwable failure) {
        active.startupFailed();
        return;
      }
      if (!active.startupSucceeded()) {
        producer.interrupt();
        return;
      }
      consume(active);
    } finally {
      cleanupTurn(active);
    }
  }

  private void produce(ActiveTelegramTurn active) {
    try {
      turns.process(active.inbound(), active.buffer(), active.buffer().cancellation());
    } catch (Throwable failure) {
      // Consumer detects an absent terminal and fails closed; exception text is deliberately
      // dropped.
    } finally {
      active.producerFinished();
    }
  }

  private void consume(ActiveTelegramTurn active) {
    var renderer =
        new TelegramTerminalRenderer(active.inbound(), active.chatId(), chunker, delivery);
    try {
      while (!renderer.isTerminal()) {
        var next = active.buffer().poll(properties.pollTimeout());
        if (next.isPresent()) {
          renderer.accept(next.orElseThrow());
          continue;
        }
        if (active.producerIsDone() && active.buffer().size() == 0) {
          if (!active.buffer().isTerminal()) {
            active.buffer().disconnect();
          }
          return;
        }
      }
    } catch (Throwable failure) {
      if (!active.buffer().isTerminal()) {
        active.buffer().disconnect();
      }
    } finally {
      if (!active.awaitProducer(properties.shutdownTimeout())) {
        active.buffer().shutdown();
        active.interruptWorkers();
      }
    }
  }

  private void sendFixed(long chatId, String text) {
    try {
      delivery.send(chatId, text);
    } catch (Throwable failure) {
      code.compareAndSet("", "DELIVERY_FAILED");
    }
  }

  private void markSafe(long updateId) {
    if (seenUpdates.add(updateId)) {
      seenOrder.addLast(updateId);
      if (seenOrder.size() > SEEN_UPDATE_LIMIT) {
        seenUpdates.remove(seenOrder.removeFirst());
      }
    }
    nextOffset = Math.max(nextOffset, updateId + 1);
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

  private void failPermanently(String failureCode) {
    accepting.set(false);
    code.set(failureCode);
    state.set(ChannelState.FAILED);
    activeTurns.values().forEach(turn -> turn.buffer().disconnect());
  }

  private void cleanupTurn(ActiveTelegramTurn active) {
    if (!active.markCleaned()) {
      return;
    }
    activeTurns.remove(active.inbound().sessionId(), active);
    turnPermits.release();
  }

  private static boolean retryable(TelegramApiException.Reason reason) {
    return reason == TelegramApiException.Reason.TIMEOUT
        || reason == TelegramApiException.Reason.UNAVAILABLE
        || reason == TelegramApiException.Reason.RATE_LIMITED;
  }

  private static String permanentPollCode(TelegramApiException.Reason reason) {
    return switch (reason) {
      case UNAUTHORIZED -> "POLL_UNAUTHORIZED";
      case INVALID_RESPONSE -> "POLL_INVALID_RESPONSE";
      case INTERRUPTED -> "POLL_INTERRUPTED";
      case RATE_LIMITED, TIMEOUT, UNAVAILABLE -> "POLL_RETRY_EXHAUSTED";
    };
  }

  private static boolean joinTurn(ActiveTelegramTurn turn, long deadlineNanos) {
    try {
      return turn.joinWorkersUntil(deadlineNanos);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean joinUntil(Thread thread, long deadlineNanos) {
    if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) {
      return true;
    }
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      return false;
    }
    try {
      return thread.join(Duration.ofNanos(remaining));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private enum UpdateResult {
    SAFE,
    STOPPED,
    TURN_START_FAILED,
    INVALID
  }

  private enum TurnStartResult {
    STARTED,
    BUSY,
    STOPPED,
    FAILED
  }
}
