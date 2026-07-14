package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class SideEffectBatchCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-14T05:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final SideEffectBatchCoordinator.Context CONTEXT =
      new SideEffectBatchCoordinator.Context("session-binding", "turn-1");

  @Test
  void executesReadOnlyBatchInOrderWithoutApproval() {
    var executions = new ArrayList<String>();
    ApprovalPort approvals = request -> null;
    var coordinator =
        coordinator(
            List.of(
                tool("first", "v1", ToolRisk.READ_ONLY, executions),
                tool("second", "v1", ToolRisk.READ_ONLY, executions)),
            approvals,
            ToolExecutionPolicy.registeredRisk());

    var results =
        coordinator.execute(
            CONTEXT,
            List.of(call("call-1", "first", Map.of()), call("call-2", "second", Map.of())),
            TurnCancellation.none());

    assertThat(executions).containsExactly("first", "second");
    assertThat(results).extracting(ToolResult::status).containsOnly(ToolResultStatus.SUCCESS);
  }

  @Test
  void deniesWholeMixedBatchBeforeInvokingAnyTool() {
    var executions = new ArrayList<String>();
    var requested = new ArrayList<ApprovalRequest>();
    ApprovalPort approvals =
        request -> {
          requested.add(request);
          return ApprovalDecision.deniedFor(request, NOW, "actor-reference");
        };
    var coordinator =
        coordinator(
            List.of(
                tool("lookup", "v1", ToolRisk.READ_ONLY, executions),
                tool("write_note", "v1", ToolRisk.WRITE, executions)),
            approvals,
            ToolExecutionPolicy.registeredRisk());

    var results =
        coordinator.execute(
            CONTEXT,
            List.of(
                call("call-1", "lookup", Map.of()),
                call("call-2", "write_note", Map.of("value", "fixed"))),
            TurnCancellation.none());

    assertThat(executions).isEmpty();
    assertThat(requested).singleElement().satisfies(request -> assertThat(request.risk()).isEqualTo(ToolRisk.WRITE));
    assertThat(results).extracting(ToolResult::status)
        .containsExactly(ToolResultStatus.SKIPPED, ToolResultStatus.DENIED);
    assertThat(results).extracting(ToolResult::content)
        .containsExactly("工具调用已跳过。", "工具调用未获批准。");
  }

  @Test
  void executesOnlyAfterAllApprovalsAndNeverLetsPolicyLowerRegisteredRisk() {
    var executions = new ArrayList<String>();
    var requestedRisks = new ArrayList<ToolRisk>();
    ApprovalPort approvals =
        request -> {
          requestedRisks.add(request.risk());
          return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
        };
    ToolExecutionPolicy policy =
        (definition, call) ->
            definition.name().equals("registered_write") ? ToolRisk.READ_ONLY : ToolRisk.WRITE;
    var coordinator =
        coordinator(
            List.of(
                tool("registered_write", "v1", ToolRisk.WRITE, executions),
                tool("escalated_read", "v1", ToolRisk.READ_ONLY, executions)),
            approvals,
            policy);

    var results =
        coordinator.execute(
            CONTEXT,
            List.of(
                call("call-1", "registered_write", Map.of()),
                call("call-2", "escalated_read", Map.of())),
            TurnCancellation.none());

    assertThat(requestedRisks).containsExactly(ToolRisk.WRITE, ToolRisk.WRITE);
    assertThat(executions).containsExactly("registered_write", "escalated_read");
    assertThat(results).extracting(ToolResult::status).containsOnly(ToolResultStatus.SUCCESS);
  }

  @Test
  @Tag("failure")
  void failsClosedForUnavailableNullMismatchedOrLateDecisions() {
    var executions = new ArrayList<String>();
    var tool = tool("write_note", "v1", ToolRisk.WRITE, executions);
    List<ApprovalPort> unsafePorts =
        List.of(
            request -> {
              throw new IllegalStateException("private approval failure");
            },
            request -> null,
            request ->
                new ApprovalDecision(
                    request.approvalId(),
                    "f".repeat(64),
                    io.namei.agent.kernel.approval.ApprovalDecisionStatus.APPROVED,
                    NOW,
                    "actor-reference"),
            request -> ApprovalDecision.approvedFor(request, request.expiresAt(), "actor-reference"));

    unsafePorts.forEach(
        port ->
            assertThatThrownBy(
                    () ->
                        coordinator(List.of(tool), port, ToolExecutionPolicy.registeredRisk())
                            .execute(
                                CONTEXT,
                                List.of(call("call-1", "write_note", Map.of())),
                                TurnCancellation.none()))
                .isInstanceOf(ApprovalUnavailableException.class)
                .hasMessageNotContaining("private approval failure"));

    assertThat(executions).isEmpty();
  }

  @Test
  @Tag("failure")
  void rejectsAnApprovalReusedAfterArgumentsTurnVersionOrRiskChanges() {
    var original = new AtomicReference<ApprovalRequest>();
    var executions = new ArrayList<String>();
    ApprovalPort captureAndDeny =
        request -> {
          original.set(request);
          return ApprovalDecision.deniedFor(request, NOW, "actor-reference");
        };
    coordinator(
            List.of(tool("write_note", "v1", ToolRisk.WRITE, executions)),
            captureAndDeny,
            ToolExecutionPolicy.registeredRisk())
        .execute(
            CONTEXT,
            List.of(call("call-1", "write_note", Map.of("value", "original"))),
            TurnCancellation.none());
    ApprovalPort staleApproval =
        request -> ApprovalDecision.approvedFor(original.get(), NOW, "actor-reference");

    assertRejected(
        coordinator(
            List.of(tool("write_note", "v1", ToolRisk.WRITE, executions)),
            staleApproval,
            ToolExecutionPolicy.registeredRisk()),
        CONTEXT,
        call("call-1", "write_note", Map.of("value", "changed")));
    assertRejected(
        coordinator(
            List.of(tool("write_note", "v1", ToolRisk.WRITE, executions)),
            staleApproval,
            ToolExecutionPolicy.registeredRisk()),
        new SideEffectBatchCoordinator.Context("session-binding", "turn-2"),
        call("call-1", "write_note", Map.of("value", "original")));
    assertRejected(
        coordinator(
            List.of(tool("write_note", "v2", ToolRisk.WRITE, executions)),
            staleApproval,
            ToolExecutionPolicy.registeredRisk()),
        CONTEXT,
        call("call-1", "write_note", Map.of("value", "original")));
    assertRejected(
        coordinator(
            List.of(tool("write_note", "v1", ToolRisk.WRITE, executions)),
            staleApproval,
            (definition, call) -> ToolRisk.EXTERNAL_SIDE_EFFECT),
        CONTEXT,
        call("call-1", "write_note", Map.of("value", "original")));

    assertThat(executions).isEmpty();
  }

  @Test
  void bindsApprovalToTheImmutableDefinitionSnapshotPublishedToTheModel() {
    var definitionReads = new AtomicInteger();
    Tool changingDefinition =
        new Tool() {
          @Override
          public ToolDefinition definition() {
            return new ToolDefinition(
                "write_note",
                "测试工具",
                Map.of("type", "object", "additionalProperties", false),
                ToolRisk.WRITE,
                "v" + definitionReads.incrementAndGet());
          }

          @Override
          public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success("固定结果");
          }
        };
    var registry = new ToolRegistry(List.of(changingDefinition), approvalSettings());
    var requestVersion = new AtomicReference<String>();
    ApprovalPort deny =
        request -> {
          requestVersion.set(request.toolVersion());
          return ApprovalDecision.deniedFor(request, NOW, "actor-reference");
        };
    var coordinator =
        new SideEffectBatchCoordinator(
            registry,
            deny,
            ToolExecutionPolicy.registeredRisk(),
            CLOCK,
            Duration.ofMinutes(5),
            new FixedIds(),
            new InMemorySideEffectLedger());

    coordinator.execute(
        CONTEXT,
        List.of(call("call-1", "write_note", Map.of())),
        TurnCancellation.none());

    assertThat(requestVersion.get()).isEqualTo(registry.definitions().getFirst().version());
    assertThat(definitionReads).hasValue(1);
  }

  private static void assertRejected(
      SideEffectBatchCoordinator coordinator,
      SideEffectBatchCoordinator.Context context,
      ToolCall call) {
    assertThatThrownBy(
            () -> coordinator.execute(context, List.of(call), TurnCancellation.none()))
        .isInstanceOf(ApprovalUnavailableException.class);
  }

  private static SideEffectBatchCoordinator coordinator(
      List<Tool> tools, ApprovalPort approvals, ToolExecutionPolicy policy) {
    return new SideEffectBatchCoordinator(
        new ToolRegistry(tools, approvalSettings()),
        approvals,
        policy,
        CLOCK,
        Duration.ofMinutes(5),
        new FixedIds(),
        new InMemorySideEffectLedger());
  }

  private static ToolCall call(String id, String name, Map<String, Object> arguments) {
    return new ToolCall(id, name, arguments);
  }

  private static ToolRuntimeSettings approvalSettings() {
    return new ToolRuntimeSettings(
        ToolRuntimeMode.APPROVAL_REQUIRED,
        8,
        16,
        Duration.ofSeconds(5),
        32,
        20_000);
  }

  private static Tool tool(
      String name, String version, ToolRisk risk, List<String> executions) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(
            name,
            "测试工具",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string")),
                "additionalProperties",
                false),
            risk,
            version);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        executions.add(name);
        return ToolResult.success("固定结果");
      }
    };
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
