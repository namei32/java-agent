package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class SideEffectIdempotencyTest {
  private static final Instant NOW = Instant.parse("2026-07-14T05:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final SideEffectBatchCoordinator.Context CONTEXT =
      new SideEffectBatchCoordinator.Context("session-binding", "turn-1");
  private static final ToolCall CALL = new ToolCall("call-1", "write_note", Map.of());

  @Test
  @Tag("failure")
  void concurrentlyConsumesOneApprovalOnlyOnce() throws Exception {
    var ledger = new InMemorySideEffectLedger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var invocations = new AtomicInteger();
    Tool tool =
        sideEffectTool(
            () -> {
              invocations.incrementAndGet();
              entered.countDown();
              await(release);
              return ToolResult.success("固定成功");
            });
    ApprovalPort approve =
        request -> ApprovalDecision.approvedFor(request, NOW, "actor-reference");
    var coordinator = coordinator(tool, approve, ledger);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> coordinator.execute(CONTEXT, List.of(CALL), TurnCancellation.none()));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () -> coordinator.execute(CONTEXT, List.of(CALL), TurnCancellation.none()))
          .isInstanceOf(SideEffectStateUnknownException.class);

      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS))
          .extracting(ToolResult::status)
          .containsExactly(ToolResultStatus.SUCCESS);
    }
    assertThat(invocations).hasValue(1);
  }

  @Test
  void replaysStoredSucceededAndFailedResultsWithoutInvokingAgain() {
    var successLedger = new InMemorySideEffectLedger();
    var successInvocations = new AtomicInteger();
    ApprovalPort approve =
        request -> ApprovalDecision.approvedFor(request, NOW, "actor-reference");
    var successCoordinator =
        coordinator(
            sideEffectTool(
                () -> {
                  successInvocations.incrementAndGet();
                  return ToolResult.success("固定成功");
                }),
            approve,
            successLedger);

    var first = successCoordinator.execute(CONTEXT, List.of(CALL), TurnCancellation.none());
    var replay = successCoordinator.execute(CONTEXT, List.of(CALL), TurnCancellation.none());

    assertThat(first).isEqualTo(replay);
    assertThat(successInvocations).hasValue(1);

    var failedLedger = new InMemorySideEffectLedger();
    var failedInvocations = new AtomicInteger();
    ApprovalPort seedFailed =
        request -> {
          failedLedger.seed(
              SideEffectIdentity.from(request),
              SideEffectExecutionState.FAILED,
              ToolResult.error("固定安全失败"));
          return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
        };
    var failed =
        coordinator(
                sideEffectTool(
                    () -> {
                      failedInvocations.incrementAndGet();
                      return ToolResult.success("不应执行");
                    }),
                seedFailed,
                failedLedger)
            .execute(CONTEXT, List.of(CALL), TurnCancellation.none());

    assertThat(failed).extracting(ToolResult::status).containsExactly(ToolResultStatus.ERROR);
    assertThat(failedInvocations).hasValue(0);
  }

  @Test
  @Tag("failure")
  void stopsImmediatelyForReservedRunningOrUnknownLedgerState() {
    for (SideEffectExecutionState state :
        List.of(
            SideEffectExecutionState.RESERVED,
            SideEffectExecutionState.RUNNING,
            SideEffectExecutionState.UNKNOWN)) {
      var ledger = new InMemorySideEffectLedger();
      var invocations = new AtomicInteger();
      ApprovalPort seed =
          request -> {
            ledger.seed(SideEffectIdentity.from(request), state, null);
            return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
          };

      assertThatThrownBy(
              () ->
                  coordinator(
                          sideEffectTool(
                              () -> {
                                invocations.incrementAndGet();
                                return ToolResult.success("不应执行");
                              }),
                          seed,
                          ledger)
                      .execute(CONTEXT, List.of(CALL), TurnCancellation.none()))
          .isInstanceOf(SideEffectStateUnknownException.class);
      assertThat(invocations).hasValue(0);
    }
  }

  @Test
  @Tag("failure")
  void failsBeforeInvokerWhenLedgerCannotPersistRunningState() {
    var ledger = new InMemorySideEffectLedger();
    ledger.failNextMarkRunning();
    var invocations = new AtomicInteger();
    ApprovalPort approve =
        request -> ApprovalDecision.approvedFor(request, NOW, "actor-reference");

    assertThatThrownBy(
            () ->
                coordinator(
                        sideEffectTool(
                            () -> {
                              invocations.incrementAndGet();
                              return ToolResult.success("不应执行");
                            }),
                        approve,
                        ledger)
                    .execute(CONTEXT, List.of(CALL), TurnCancellation.none()))
        .isInstanceOf(ApprovalUnavailableException.class);
    assertThat(invocations).hasValue(0);
  }

  @Test
  @Tag("failure")
  void recordsUnknownWhenInvokerMayHaveSucceededButSuccessPersistenceFails() {
    var ledger = new InMemorySideEffectLedger();
    ledger.failNextMarkSucceeded();
    var captured = new AtomicReference<ApprovalRequest>();
    ApprovalPort approve =
        request -> {
          captured.set(request);
          return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
        };
    var invocations = new AtomicInteger();

    assertThatThrownBy(
            () ->
                coordinator(
                        sideEffectTool(
                            () -> {
                              invocations.incrementAndGet();
                              return ToolResult.success("外部操作可能已成功");
                            }),
                        approve,
                        ledger)
                    .execute(CONTEXT, List.of(CALL), TurnCancellation.none()))
        .isInstanceOf(SideEffectStateUnknownException.class);

    assertThat(invocations).hasValue(1);
    assertThat(ledger.find(SideEffectIdentity.from(captured.get())))
        .get()
        .extracting(SideEffectLedger.Entry::state)
        .isEqualTo(SideEffectExecutionState.UNKNOWN);
  }

  @Test
  void onlyAllowsKnownNoEffectFailureBeforeRunningBoundary() {
    var ledger = new InMemorySideEffectLedger();
    var request = request();
    var identity = SideEffectIdentity.from(request);
    assertThat(ledger.reserve(identity, request).acquired()).isTrue();

    ledger.markFailedBeforeStart(identity, ToolResult.error("固定安全失败"));
    assertThat(ledger.find(identity)).get().extracting(SideEffectLedger.Entry::state)
        .isEqualTo(SideEffectExecutionState.FAILED);

    var otherRequest = request("approval-2", "idempotency-2");
    var otherIdentity = SideEffectIdentity.from(otherRequest);
    ledger.reserve(otherIdentity, otherRequest);
    ledger.markRunning(otherIdentity);
    assertThatThrownBy(
            () ->
                ledger.markFailedBeforeStart(
                    otherIdentity, ToolResult.error("不能证明未发生副作用")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new SideEffectLedger.Entry(
                    identity,
                    SideEffectExecutionState.FAILED,
                    ToolResult.success("状态不匹配"),
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SideEffectLedger.Entry(
                    identity,
                    SideEffectExecutionState.SUCCEEDED,
                    ToolResult.error("状态不匹配"),
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SideEffectBatchCoordinator coordinator(
      Tool tool, ApprovalPort approvals, SideEffectLedger ledger) {
    return new SideEffectBatchCoordinator(
        new ToolRegistry(
            List.of(tool),
            new ToolRuntimeSettings(
                ToolRuntimeMode.APPROVAL_REQUIRED,
                8,
                16,
                Duration.ofSeconds(5),
                32,
                20_000)),
        approvals,
        ToolExecutionPolicy.registeredRisk(),
        CLOCK,
        Duration.ofMinutes(5),
        new FixedIds(),
        ledger);
  }

  private static Tool sideEffectTool(java.util.function.Supplier<ToolResult> invocation) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(
            "write_note",
            "测试副作用工具",
            Map.of("type", "object", "additionalProperties", false),
            ToolRisk.WRITE);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        return invocation.get();
      }
    };
  }

  private static ApprovalRequest request() {
    return request("approval-fixed", "idempotency-fixed");
  }

  private static ApprovalRequest request(String approvalId, String idempotencyKey) {
    String argumentsHash = ApprovalFingerprint.argumentsHash(Map.of());
    Instant expiresAt = NOW.plus(Duration.ofMinutes(5));
    String fingerprint =
        ApprovalFingerprint.calculate(
            "session-binding",
            "turn-1",
            "call-1",
            "write_note",
            "v1",
            ToolRisk.WRITE,
            argumentsHash,
            idempotencyKey,
            NOW,
            expiresAt);
    return new ApprovalRequest(
        approvalId,
        "session-binding",
        "turn-1",
        "call-1",
        "write_note",
        "v1",
        ToolRisk.WRITE,
        argumentsHash,
        idempotencyKey,
        "固定摘要",
        NOW,
        expiresAt,
        ApprovalRequest.FINGERPRINT_VERSION,
        fingerprint);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("测试同步超时");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("测试同步被中断", exception);
    }
  }

  private static final class FixedIds implements IdGenerator {
    @Override
    public String newTurnId() {
      return "turn-fixed";
    }

    @Override
    public String newApprovalId() {
      return "approval-fixed";
    }

    @Override
    public String newIdempotencyKey() {
      return "idempotency-fixed";
    }
  }
}
