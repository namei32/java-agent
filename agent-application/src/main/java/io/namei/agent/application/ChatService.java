package io.namei.agent.application;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelContextLimitException;
import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.error.TurnCancelledException;
import io.namei.agent.kernel.history.ConversationHistorySelector;
import io.namei.agent.kernel.history.HistoryLimits;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import io.namei.agent.kernel.model.ModelMessage;
import io.namei.agent.kernel.model.PersistedTurn;
import io.namei.agent.kernel.port.ChatModelPort;
import io.namei.agent.kernel.port.ProviderUsageObserver;
import io.namei.agent.kernel.port.SessionRepository;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.port.TurnLifecycleObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent 被动对话主流程的应用服务。
 *
 * <p>该类在单个会话执行门内依次完成：加载历史、组装记忆和提示词、调用模型/工具循环、处理上下文超限恢复、检查取消、原子提交完整 Turn，并发布生命周期与 Provider
 * Usage。任何模型或工具异常都会在提交前终止，避免产生半个轮次。
 */
public final class ChatService implements ChatUseCase {
  private final SessionRepository sessions;
  private final ConversationHistorySelector historySelector;
  private final HistoryLimits limits;
  private final SessionExecutionGate gate;
  private final String systemPrompt;
  private final Clock clock;
  private final ToolLoop toolLoop;
  private final LifecyclePublisher lifecycle;
  private final IdGenerator ids;
  private final MemoryContextService memoryContext;
  private final ConversationEvidenceContextFactory conversationEvidenceContexts;
  private final MemoryRecallContextFactory memoryRecallContexts;
  private final ContextLimitRecoveryPolicy contextLimitRecovery;
  private final ProviderUsageObserver providerUsageObserver;

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        List.of(),
        1,
        TurnLifecycleObserver.noop());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        tools,
        maxIterations,
        observer,
        ToolRuntimeSettings.readOnlyDefaults());
  }

  /** 供 R10-P3 本地恢复路径测试使用的显式选择加入构造器。 */
  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer,
      ContextLimitRecoveryPolicy contextLimitRecovery) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        tools,
        maxIterations,
        observer,
        contextLimitRecovery,
        ProviderUsageObserver.disabled());
  }

  /** 供匿名已提交 Turn Provider Usage 观察测试使用的显式选择加入构造器。 */
  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer,
      ContextLimitRecoveryPolicy contextLimitRecovery,
      ProviderUsageObserver providerUsageObserver) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        ToolCatalog.alwaysOn(List.copyOf(tools)),
        maxIterations,
        observer,
        ToolRuntimeSettings.readOnlyDefaults(),
        request -> ApprovalDecision.deniedFor(request, clock.instant(), "deny-all"),
        SideEffectLedger.unavailable(),
        new SecureIdGenerator(),
        Duration.ofMinutes(5),
        MemoryContextService.disabled(),
        ModelStreamingSettings.defaults(),
        ConversationEvidenceContextFactory.disabled(),
        MemoryRecallContextFactory.disabled(),
        contextLimitRecovery,
        providerUsageObserver);
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      ToolCatalog catalog,
      int maxIterations,
      TurnLifecycleObserver observer) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        catalog,
        maxIterations,
        observer,
        ToolRuntimeSettings.readOnlyDefaults(),
        request -> ApprovalDecision.deniedFor(request, clock.instant(), "deny-all"),
        SideEffectLedger.unavailable(),
        new SecureIdGenerator(),
        Duration.ofMinutes(5),
        MemoryContextService.disabled(),
        ModelStreamingSettings.defaults());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        tools,
        maxIterations,
        observer,
        toolSettings,
        request -> ApprovalDecision.deniedFor(request, clock.instant(), "deny-all"),
        SideEffectLedger.unavailable(),
        new SecureIdGenerator(),
        Duration.ofMinutes(5),
        MemoryContextService.disabled());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer,
      MemoryContextService memoryContext) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        tools,
        maxIterations,
        observer,
        ToolRuntimeSettings.readOnlyDefaults(),
        request -> ApprovalDecision.deniedFor(request, clock.instant(), "deny-all"),
        SideEffectLedger.unavailable(),
        new SecureIdGenerator(),
        Duration.ofMinutes(5),
        memoryContext);
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        tools,
        maxIterations,
        observer,
        toolSettings,
        approvals,
        ledger,
        ids,
        approvalTimeout,
        MemoryContextService.disabled());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout,
      MemoryContextService memoryContext) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        tools,
        maxIterations,
        observer,
        toolSettings,
        approvals,
        ledger,
        ids,
        approvalTimeout,
        memoryContext,
        ModelStreamingSettings.defaults());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      List<Tool> tools,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout,
      MemoryContextService memoryContext,
      ModelStreamingSettings streamingSettings) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        ToolCatalog.alwaysOn(List.copyOf(tools)),
        maxIterations,
        observer,
        toolSettings,
        approvals,
        ledger,
        ids,
        approvalTimeout,
        memoryContext,
        streamingSettings,
        ConversationEvidenceContextFactory.disabled());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      ToolCatalog catalog,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout,
      MemoryContextService memoryContext,
      ModelStreamingSettings streamingSettings) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        catalog,
        maxIterations,
        observer,
        toolSettings,
        approvals,
        ledger,
        ids,
        approvalTimeout,
        memoryContext,
        streamingSettings,
        ConversationEvidenceContextFactory.disabled());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      ToolCatalog catalog,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout,
      MemoryContextService memoryContext,
      ModelStreamingSettings streamingSettings,
      ConversationEvidenceContextFactory conversationEvidenceContexts) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        catalog,
        maxIterations,
        observer,
        toolSettings,
        approvals,
        ledger,
        ids,
        approvalTimeout,
        memoryContext,
        streamingSettings,
        conversationEvidenceContexts,
        MemoryRecallContextFactory.disabled());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      ToolCatalog catalog,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout,
      MemoryContextService memoryContext,
      ModelStreamingSettings streamingSettings,
      ConversationEvidenceContextFactory conversationEvidenceContexts,
      MemoryRecallContextFactory memoryRecallContexts) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        catalog,
        maxIterations,
        observer,
        toolSettings,
        approvals,
        ledger,
        ids,
        approvalTimeout,
        memoryContext,
        streamingSettings,
        conversationEvidenceContexts,
        memoryRecallContexts,
        ContextLimitRecoveryPolicy.disabled());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      ToolCatalog catalog,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout,
      MemoryContextService memoryContext,
      ModelStreamingSettings streamingSettings,
      ConversationEvidenceContextFactory conversationEvidenceContexts,
      MemoryRecallContextFactory memoryRecallContexts,
      ContextLimitRecoveryPolicy contextLimitRecovery) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        catalog,
        maxIterations,
        observer,
        toolSettings,
        approvals,
        ledger,
        ids,
        approvalTimeout,
        memoryContext,
        streamingSettings,
        conversationEvidenceContexts,
        memoryRecallContexts,
        contextLimitRecovery,
        ProviderUsageObserver.disabled());
  }

  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      ToolCatalog catalog,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout,
      MemoryContextService memoryContext,
      ModelStreamingSettings streamingSettings,
      ConversationEvidenceContextFactory conversationEvidenceContexts,
      MemoryRecallContextFactory memoryRecallContexts,
      ContextLimitRecoveryPolicy contextLimitRecovery,
      ProviderUsageObserver providerUsageObserver) {
    this(
        sessions,
        model,
        historySelector,
        limits,
        gate,
        systemPrompt,
        clock,
        catalog,
        maxIterations,
        observer,
        toolSettings,
        approvals,
        ledger,
        ids,
        approvalTimeout,
        memoryContext,
        streamingSettings,
        conversationEvidenceContexts,
        memoryRecallContexts,
        contextLimitRecovery,
        providerUsageObserver,
        MemoryForgetPendingToolset.disabled());
  }

  /** Production-only composition constructor for the dedicated Pending Producer path. */
  public ChatService(
      SessionRepository sessions,
      ChatModelPort model,
      ConversationHistorySelector historySelector,
      HistoryLimits limits,
      SessionExecutionGate gate,
      String systemPrompt,
      Clock clock,
      ToolCatalog catalog,
      int maxIterations,
      TurnLifecycleObserver observer,
      ToolRuntimeSettings toolSettings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      IdGenerator ids,
      Duration approvalTimeout,
      MemoryContextService memoryContext,
      ModelStreamingSettings streamingSettings,
      ConversationEvidenceContextFactory conversationEvidenceContexts,
      MemoryRecallContextFactory memoryRecallContexts,
      ContextLimitRecoveryPolicy contextLimitRecovery,
      ProviderUsageObserver providerUsageObserver,
      MemoryForgetPendingToolset pendingToolset) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.historySelector = Objects.requireNonNull(historySelector, "historySelector");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.gate = Objects.requireNonNull(gate, "gate");
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.lifecycle = new LifecyclePublisher(observer);
    this.ids = Objects.requireNonNull(ids, "ids");
    this.memoryContext = Objects.requireNonNull(memoryContext, "memoryContext");
    this.conversationEvidenceContexts =
        Objects.requireNonNull(conversationEvidenceContexts, "conversationEvidenceContexts");
    this.memoryRecallContexts =
        Objects.requireNonNull(memoryRecallContexts, "memoryRecallContexts");
    this.contextLimitRecovery =
        Objects.requireNonNull(contextLimitRecovery, "contextLimitRecovery");
    this.providerUsageObserver =
        Objects.requireNonNull(providerUsageObserver, "providerUsageObserver");
    var registry = new ToolRegistry(catalog, toolSettings);
    var coordinator =
        new SideEffectBatchCoordinator(
            registry,
            approvals,
            ToolExecutionPolicy.registeredRisk(),
            clock,
            approvalTimeout,
            ids,
            ledger,
            lifecycle);
    this.toolLoop =
        new ToolLoop(
            model,
            registry,
            lifecycle,
            maxIterations,
            toolSettings,
            coordinator,
            streamingSettings,
            pendingToolset);
  }

  /** 使用默认的不可取消句柄执行对话。 */
  @Override
  public ChatResult chat(ChatCommand command) {
    return chat(command, TurnCancellation.none());
  }

  /**
   * 在会话级执行门内运行对话，防止同一 Session 的历史读取和提交发生竞争。
   *
   * @param command 用户输入及会话上下文
   * @param cancellation 可由渠道或控制面触发的协作取消句柄
   * @return 已成功持久化的助手结果
   */
  @Override
  public ChatResult chat(ChatCommand command, TurnCancellation cancellation) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(cancellation, "cancellation");
    return gate.execute(command.sessionId(), () -> execute(command, cancellation));
  }

  /** 执行带流式进度回调的对话。进度只用于外部展示，最终返回前仍会执行取消检查和原子持久化。 */
  @Override
  public ChatResult chat(
      ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(cancellation, "cancellation");
    Objects.requireNonNull(progressListener, "progressListener");
    return gate.execute(
        command.sessionId(), () -> execute(command, cancellation, progressListener));
  }

  private ChatResult execute(ChatCommand command, TurnCancellation cancellation) {
    return execute(command, cancellation, null);
  }

  /** 执行门内部的完整轮次事务边界；只有模型、工具和取消检查全部成功后才写入会话。 */
  private ChatResult execute(
      ChatCommand command, TurnCancellation cancellation, ChatProgressListener progressListener) {
    lifecycle.emit(TurnLifecycleEvent.turnStarted());
    try {
      var snapshot = sessions.load(command.sessionId());
      var user = new ChatMessage(MessageRole.USER, command.message());
      String sessionBinding = ApprovalFingerprint.sessionBinding(command.sessionId());
      OffsetDateTime userAt = OffsetDateTime.now(clock);
      var context = new SideEffectBatchCoordinator.Context(sessionBinding, ids.newTurnId());
      var invocationContext = conversationEvidenceContexts.forSession(command.sessionId());
      invocationContext = memoryRecallContexts.forSessionBinding(sessionBinding, invocationContext);
      var usageCollector = new ProviderTurnUsageCollector();
      ToolLoopCompletion completion =
          completeWithContextLimitRecovery(
              command,
              snapshot.messages(),
              snapshot.nextSequence(),
              user,
              userAt,
              sessionBinding,
              context,
              invocationContext,
              cancellation,
              progressListener,
              usageCollector);
      if (completion instanceof ToolLoopCompletion.Pending pending) {
        var assistant = new ChatMessage(MessageRole.ASSISTANT, pending.assistantProjection());
        lifecycle.emit(TurnLifecycleEvent.turnCommitted());
        publishProviderUsage(usageCollector);
        return new ChatResult(command.sessionId(), assistant);
      }
      String finalContent = ((ToolLoopCompletion.Final) completion).content();
      if (finalContent.isBlank()) {
        throw new InvalidModelResponseException("模型返回了空响应");
      }
      var assistant = new ChatMessage(MessageRole.ASSISTANT, finalContent);
      var turn = new PersistedTurn(user, userAt, assistant, OffsetDateTime.now(clock));
      checkCancellation(cancellation);
      lifecycle.emit(TurnLifecycleEvent.turnCommitting());
      sessions.appendTurn(command.sessionId(), turn);
      lifecycle.emit(TurnLifecycleEvent.turnCommitted());
      publishProviderUsage(usageCollector);
      return new ChatResult(command.sessionId(), assistant);
    } catch (RuntimeException failure) {
      lifecycle.emit(TurnLifecycleEvent.turnFailed(failureStatus(failure)));
      throw failure;
    }
  }

  /**
   * 按恢复策略逐步缩减历史并重试上下文超限失败。
   *
   * <p>仅 {@link ModelContextLimitException} 可以进入下一恢复方案，其他失败保持原语义直接抛出。
   */
  private ToolLoopCompletion completeWithContextLimitRecovery(
      ChatCommand command,
      List<ChatMessage> fullHistory,
      long expectedNextSequence,
      ChatMessage user,
      OffsetDateTime userAt,
      String sessionBinding,
      SideEffectBatchCoordinator.Context context,
      ToolInvocationContext invocationContext,
      TurnCancellation cancellation,
      ChatProgressListener progressListener,
      ProviderTurnUsageCollector usageCollector) {
    List<ChatMessage> selectedHistory = historySelector.select(fullHistory, limits);
    ModelContextLimitException lastContextLimit = null;
    for (ContextLimitRecoveryPolicy.Plan plan :
        contextLimitRecovery.plans(selectedHistory.size())) {
      checkCancellation(cancellation);
      List<ChatMessage> history = trailingHistory(selectedHistory, plan.historySize());
      var assembled =
          memoryContext.assemble(
              systemPrompt,
              sessionBinding,
              command.sessionId(),
              fullHistory,
              history,
              user,
              clock.instant(),
              command.promptTurnContext(),
              plan.trimPlan());
      var messages = new ArrayList<ModelMessage>(assembled.messages());
      try {
        if (!toolLoop.pendingProducerEnabled()) {
          String finalContent =
              progressListener == null
                  ? toolLoop.complete(
                      messages, cancellation, context, invocationContext, usageCollector)
                  : toolLoop.completeStreaming(
                      messages,
                      cancellation,
                      context,
                      invocationContext,
                      progressListener,
                      usageCollector);
          return new ToolLoopCompletion.Final(finalContent);
        }
        var pendingTurnContext =
            new MemoryForgetPendingTurnContext(
                command.sessionId(), expectedNextSequence, context.turnId(), user, userAt, clock);
        return progressListener == null
            ? toolLoop.completeForPendingTurn(
                messages,
                cancellation,
                context,
                invocationContext,
                pendingTurnContext,
                usageCollector)
            : toolLoop.completeStreamingForPendingTurn(
                messages,
                cancellation,
                context,
                invocationContext,
                pendingTurnContext,
                progressListener,
                usageCollector);
      } catch (ContextLimitRecoveryCandidateException candidate) {
        lastContextLimit = candidate.original();
      }
    }
    if (lastContextLimit != null) {
      throw lastContextLimit;
    }
    throw new IllegalStateException("上下文恢复候选不能为空");
  }

  private static List<ChatMessage> trailingHistory(List<ChatMessage> history, int size) {
    if (size < 0 || size > history.size()) {
      throw new IllegalArgumentException("恢复历史窗口无效");
    }
    if (size == history.size()) {
      return history;
    }
    return List.copyOf(history.subList(history.size() - size, history.size()));
  }

  private static String failureStatus(RuntimeException failure) {
    if (failure instanceof TurnCancelledException) {
      return "TURN_CANCELLED";
    }
    if (failure instanceof ToolCallLimitExceededException) {
      return "TOOL_CALL_LIMIT_EXCEEDED";
    }
    if (failure instanceof ToolLoopLimitExceededException) {
      return "TOOL_LOOP_LIMIT_EXCEEDED";
    }
    if (failure instanceof ModelStreamLimitExceededException) {
      return "MODEL_STREAM_LIMIT_EXCEEDED";
    }
    if (failure instanceof InvalidModelResponseException) {
      return "INVALID_MODEL_RESPONSE";
    }
    if (failure instanceof ApprovalUnavailableException) {
      return "APPROVAL_UNAVAILABLE";
    }
    if (failure instanceof SideEffectStateUnknownException) {
      return "SIDE_EFFECT_STATE_UNKNOWN";
    }
    if (failure instanceof MemoryContextUnavailableException) {
      return "MEMORY_CONTEXT_UNAVAILABLE";
    }
    return "TURN_EXECUTION_FAILED";
  }

  private void publishProviderUsage(ProviderTurnUsageCollector usageCollector) {
    try {
      providerUsageObserver.onCommittedTurn(usageCollector.snapshot());
    } catch (RuntimeException ignored) {
      // 观察有意仅作尽力执行，不能回滚已提交 Turn。
    }
  }

  private static void checkCancellation(TurnCancellation cancellation) {
    if (cancellation.isCancellationRequested()) {
      throw new TurnCancelledException("当前 Turn 已取消");
    }
  }
}
