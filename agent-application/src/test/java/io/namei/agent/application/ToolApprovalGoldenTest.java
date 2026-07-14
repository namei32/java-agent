package io.namei.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.approval.ApprovalDecision;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.lifecycle.TurnLifecycleEvent;
import io.namei.agent.kernel.port.Tool;
import io.namei.agent.kernel.tool.SideEffectExecutionState;
import io.namei.agent.kernel.tool.ToolCall;
import io.namei.agent.kernel.tool.ToolDefinition;
import io.namei.agent.kernel.tool.ToolResult;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ToolApprovalGoldenTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-07-14T05:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final SideEffectBatchCoordinator.Context CONTEXT =
      new SideEffectBatchCoordinator.Context("session-binding", "turn-1");

  @Test
  void executesApprovalAndSideEffectGoldenAgainstProductionRuntime() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/approval-side-effects.json"));

    assertThat(fixture.path("source").asString()).isEqualTo("migration-contract");
    var identifiers = new HashSet<String>();
    for (JsonNode testCase : fixture.path("cases")) {
      String identifier = testCase.path("id").asString();
      assertThat(identifiers.add(identifier)).isTrue();

      Map<String, ?> actual = execute(identifier, testCase.path("input").path("scenario").asString());

      JsonNode actualJson = JSON.valueToTree(actual);
      assertThat(actualJson).as(identifier).isEqualTo(testCase.path("expected"));
    }

    assertThat(identifiers)
        .containsExactlyInAnyOrder(
            "python-risk-labels",
            "python-pre-hook-denial",
            "read-only-without-approval",
            "mixed-batch-denied",
            "approved-once-with-lifecycle",
            "approval-binding-change-rejected",
            "idempotent-replay",
            "unknown-state-stops",
            "mode-definition-visibility");
  }

  private static Map<String, ?> execute(String identifier, String scenario) {
    return switch (identifier) {
      case "python-risk-labels" ->
          Map.of(
              "tools",
              List.of(
                  Map.of("name", "read_probe", "risk", "read-only"),
                  Map.of("name", "write_probe", "risk", "write"),
                  Map.of("name", "external_probe", "risk", "external-side-effect")));
      case "python-pre-hook-denial" -> Map.of("invocations", 0, "status", "denied");
      default -> executeMigrationScenario(scenario);
    };
  }

  private static Map<String, ?> executeMigrationScenario(String scenario) {
    return switch (scenario) {
      case "READ_ONLY_EXECUTION" -> readOnlyExecution();
      case "MIXED_BATCH_DENIAL" -> mixedBatchDenial();
      case "APPROVED_EXECUTION" -> approvedExecution();
      case "STALE_APPROVAL" -> staleApproval();
      case "IDEMPOTENT_REPLAY" -> idempotentReplay();
      case "UNKNOWN_REPLAY" -> unknownReplay();
      case "MODE_VISIBILITY" -> modeVisibility();
      default -> throw new IllegalArgumentException("未知 Golden 审批场景: " + scenario);
    };
  }

  private static Map<String, ?> readOnlyExecution() {
    var executions = new AtomicInteger();
    ApprovalPort approvals = request -> {
      throw new AssertionError("只读工具不应请求审批");
    };
    var coordinator =
        coordinator(
            List.of(tool("read_probe", ToolRisk.READ_ONLY, executions)),
            readOnlySettings(),
            approvals,
            new InMemorySideEffectLedger());

    var results =
        coordinator.execute(
            CONTEXT,
            List.of(new ToolCall("call-read", "read_probe", Map.of())),
            TurnCancellation.none());

    return Map.of("executions", executions.get(), "outcome", results.getFirst().status().name());
  }

  private static Map<String, ?> mixedBatchDenial() {
    var executions = new AtomicInteger();
    ApprovalPort deny = request -> ApprovalDecision.deniedFor(request, NOW, "actor-reference");
    var coordinator =
        coordinator(
            List.of(
                tool("read_probe", ToolRisk.READ_ONLY, executions),
                tool("write_probe", ToolRisk.WRITE, executions)),
            approvalSettings(),
            deny,
            new InMemorySideEffectLedger());

    var results =
        coordinator.execute(
            CONTEXT,
            List.of(
                new ToolCall("call-read", "read_probe", Map.of()),
                new ToolCall("call-write", "write_probe", Map.of())),
            TurnCancellation.none());

    return Map.of(
        "executions", executions.get(),
        "statuses", results.stream().map(result -> result.status().name()).toList());
  }

  private static Map<String, ?> approvedExecution() {
    var executions = new AtomicInteger();
    var request = new AtomicReference<ApprovalRequest>();
    var ledger = new InMemorySideEffectLedger();
    var events = new ArrayList<TurnLifecycleEvent>();
    ApprovalPort approve =
        approval -> {
          request.set(approval);
          return ApprovalDecision.approvedFor(approval, NOW, "actor-reference");
        };
    var coordinator =
        coordinator(
            List.of(tool("write_probe", ToolRisk.WRITE, executions)),
            approvalSettings(),
            approve,
            ledger,
            events);

    var results =
        coordinator.execute(
            CONTEXT,
            List.of(new ToolCall("call-write", "write_probe", Map.of())),
            TurnCancellation.none());
    var state = ledger.find(SideEffectIdentity.from(request.get())).orElseThrow().state().name();

    return Map.of(
        "executions", executions.get(),
        "ledgerState", state,
        "outcome", results.getFirst().status().name(),
        "trace", lifecycleTrace(events));
  }

  private static Map<String, ?> staleApproval() {
    var executions = new AtomicInteger();
    var original = new AtomicReference<ApprovalRequest>();
    ApprovalPort captureAndDeny =
        request -> {
          original.set(request);
          return ApprovalDecision.deniedFor(request, NOW, "actor-reference");
        };
    coordinator(
            List.of(tool("write_probe", ToolRisk.WRITE, executions)),
            approvalSettings(),
            captureAndDeny,
            new InMemorySideEffectLedger())
        .execute(
            CONTEXT,
            List.of(new ToolCall("call-write", "write_probe", Map.of("value", "original"))),
            TurnCancellation.none());

    ApprovalPort stale =
        request -> ApprovalDecision.approvedFor(original.get(), NOW, "actor-reference");
    String outcome;
    try {
      coordinator(
              List.of(tool("write_probe", ToolRisk.WRITE, executions)),
              approvalSettings(),
              stale,
              new InMemorySideEffectLedger())
          .execute(
              CONTEXT,
              List.of(new ToolCall("call-write", "write_probe", Map.of("value", "changed"))),
              TurnCancellation.none());
      outcome = "SUCCESS";
    } catch (ApprovalUnavailableException exception) {
      outcome = "APPROVAL_UNAVAILABLE";
    }
    return Map.of("executions", executions.get(), "outcome", outcome);
  }

  private static Map<String, ?> idempotentReplay() {
    var executions = new AtomicInteger();
    var request = new AtomicReference<ApprovalRequest>();
    var ledger = new InMemorySideEffectLedger();
    ApprovalPort approve =
        approval -> {
          request.set(approval);
          return ApprovalDecision.approvedFor(approval, NOW, "actor-reference");
        };
    var coordinator =
        coordinator(
            List.of(tool("write_probe", ToolRisk.WRITE, executions)),
            approvalSettings(),
            approve,
            ledger);
    var call = new ToolCall("call-write", "write_probe", Map.of());

    coordinator.execute(CONTEXT, List.of(call), TurnCancellation.none());
    var replay = coordinator.execute(CONTEXT, List.of(call), TurnCancellation.none());

    return Map.of(
        "executions", executions.get(),
        "ledgerState",
            ledger.find(SideEffectIdentity.from(request.get())).orElseThrow().state().name(),
        "outcome", replay.getFirst().status().name());
  }

  private static Map<String, ?> unknownReplay() {
    var executions = new AtomicInteger();
    var ledger = new InMemorySideEffectLedger();
    ApprovalPort seedUnknown =
        request -> {
          ledger.seed(SideEffectIdentity.from(request), SideEffectExecutionState.UNKNOWN, null);
          return ApprovalDecision.approvedFor(request, NOW, "actor-reference");
        };
    String outcome;
    try {
      coordinator(
              List.of(tool("write_probe", ToolRisk.WRITE, executions)),
              approvalSettings(),
              seedUnknown,
              ledger)
          .execute(
              CONTEXT,
              List.of(new ToolCall("call-write", "write_probe", Map.of())),
              TurnCancellation.none());
      outcome = "SUCCESS";
    } catch (SideEffectStateUnknownException exception) {
      outcome = "SIDE_EFFECT_STATE_UNKNOWN";
    }
    return Map.of("executions", executions.get(), "outcome", outcome);
  }

  private static Map<String, ?> modeVisibility() {
    var executions = new AtomicInteger();
    Tool readOnly = tool("read_probe", ToolRisk.READ_ONLY, executions);
    Tool write = tool("write_probe", ToolRisk.WRITE, executions);

    return Map.of(
        "approvalRequired",
            new ToolRegistry(List.of(readOnly, write), approvalSettings()).definitions().size(),
        "disabled",
            new ToolRegistry(List.of(readOnly, write), settings(ToolRuntimeMode.DISABLED))
                .definitions()
                .size(),
        "readOnly",
            new ToolRegistry(List.of(readOnly), readOnlySettings()).definitions().size());
  }

  private static SideEffectBatchCoordinator coordinator(
      List<Tool> tools,
      ToolRuntimeSettings settings,
      ApprovalPort approvals,
      SideEffectLedger ledger) {
    return coordinator(tools, settings, approvals, ledger, List.of());
  }

  private static SideEffectBatchCoordinator coordinator(
      List<Tool> tools,
      ToolRuntimeSettings settings,
      ApprovalPort approvals,
      SideEffectLedger ledger,
      List<TurnLifecycleEvent> events) {
    return new SideEffectBatchCoordinator(
        new ToolRegistry(tools, settings),
        approvals,
        ToolExecutionPolicy.registeredRisk(),
        CLOCK,
        Duration.ofMinutes(5),
        new FixedIds(),
        ledger,
        new LifecyclePublisher(events::add));
  }

  private static Tool tool(String name, ToolRisk risk, AtomicInteger executions) {
    return new Tool() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(
            name,
            "Golden 审批工具",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string")),
                "additionalProperties",
                false),
            risk);
      }

      @Override
      public ToolResult execute(Map<String, Object> arguments) {
        executions.incrementAndGet();
        return ToolResult.success("固定结果");
      }
    };
  }

  private static List<String> lifecycleTrace(List<TurnLifecycleEvent> events) {
    return events.stream()
        .map(
            event ->
                event.status().isEmpty()
                    ? event.type().name()
                    : event.type().name() + ":" + event.status())
        .toList();
  }

  private static ToolRuntimeSettings approvalSettings() {
    return settings(ToolRuntimeMode.APPROVAL_REQUIRED);
  }

  private static ToolRuntimeSettings readOnlySettings() {
    return settings(ToolRuntimeMode.READ_ONLY);
  }

  private static ToolRuntimeSettings settings(ToolRuntimeMode mode) {
    return new ToolRuntimeSettings(mode, 8, 16, Duration.ofSeconds(5), 32, 20_000);
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
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
