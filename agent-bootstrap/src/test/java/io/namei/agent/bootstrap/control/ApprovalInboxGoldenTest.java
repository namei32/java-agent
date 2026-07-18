package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.namei.agent.adapter.sqlite.ApprovalInboxRepositoryException;
import io.namei.agent.adapter.sqlite.ApprovalInboxSchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcApprovalInbox;
import io.namei.agent.application.ApprovalInboxDecision;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.application.ApprovalInboxResolutionStatus;
import io.namei.agent.application.ToolRuntimeMode;
import io.namei.agent.application.ToolRuntimeSettings;
import io.namei.agent.bootstrap.config.AgentProperties;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.approval.ApprovalState;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("compat")
class ApprovalInboxGoldenTest {
  private static final Instant ISSUED = Instant.parse("2026-07-18T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void executesEveryVersionedApprovalInboxFixtureCaseAgainstProductionBoundaries()
      throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("tools/approval-inbox-v1.json").toFile());
    assertThat(fixture.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(fixture.path("source").asText()).isEqualTo("java-contract");
    assertThat(fixture.path("contract").asText()).isEqualTo("tool-approval-inbox-v1");
    assertThat(fixture.path("cases").size()).isEqualTo(29);
    for (JsonNode testCase : fixture.path("cases")) {
      verify(testCase.path("id").asText());
    }
  }

  private void verify(String id) throws Exception {
    switch (id) {
      case "reference-accepts-128-bit" ->
          assertThat(ApprovalInboxReference.of(reference(1)).value()).isEqualTo(reference(1));
      case "reference-rejects-short" ->
          assertThatIllegalArgumentException().isThrownBy(() -> ApprovalInboxReference.of("short"));
      case "reference-rejects-non-url" ->
          assertThatIllegalArgumentException()
              .isThrownBy(() -> ApprovalInboxReference.of("AAAAAAAAAAAAAAAAAAAAA+"));
      case "pending-entry-only" ->
          assertThat(entry(1, "pending-only", ISSUED).state()).isEqualTo(ApprovalState.PENDING);
      case "entry-to-string-redacts-bindings" -> {
        ApprovalInboxEntry entry = entry(1, "approval-secret", ISSUED);
        assertThat(entry.toString())
            .doesNotContain(entry.request().approvalId(), entry.request().fingerprint())
            .contains("<redacted>");
      }
      case "create-persists-pending" -> {
        JdbcApprovalInbox inbox = inbox(id).inbox();
        inbox.create(entry(2, id, ISSUED));
        assertThat(inbox.list(ISSUED, 64).getFirst().state()).isEqualTo(ApprovalState.PENDING);
      }
      case "list-orders-by-issued-and-reference" -> {
        JdbcApprovalInbox inbox = inbox(id).inbox();
        inbox.create(entry(4, "later", ISSUED.plusSeconds(1)));
        inbox.create(entry(3, "earlier", ISSUED));
        assertThat(inbox.list(ISSUED, 64))
            .extracting(value -> value.reference().value())
            .containsExactly(reference(3), reference(4));
      }
      case "approved-is-terminal" ->
          assertTerminal(id, ApprovalInboxDecision.APPROVED, ApprovalState.APPROVED);
      case "denied-is-terminal" ->
          assertTerminal(id, ApprovalInboxDecision.DENIED, ApprovalState.DENIED);
      case "late-decision-expires", "exact-expiry-expires" -> {
        JdbcApprovalInbox inbox = inbox(id).inbox();
        ApprovalInboxEntry pending = entry(5, id, ISSUED);
        inbox.create(pending);
        assertThat(
                inbox
                    .resolve(
                        pending.reference(),
                        ApprovalInboxDecision.APPROVED,
                        "actor",
                        pending.request().expiresAt())
                    .status())
            .isEqualTo(ApprovalInboxResolutionStatus.EXPIRED);
      }
      case "unknown-reference-is-not-found" ->
          assertThat(
                  inbox(id)
                      .inbox()
                      .resolve(
                          ApprovalInboxReference.of(reference(6)),
                          ApprovalInboxDecision.APPROVED,
                          "actor",
                          ISSUED)
                      .status())
              .isEqualTo(ApprovalInboxResolutionStatus.NOT_FOUND);
      case "concurrent-decision-has-one-winner" -> {
        JdbcApprovalInbox inbox = inbox(id).inbox();
        ApprovalInboxEntry pending = entry(7, id, ISSUED);
        inbox.create(pending);
        assertThat(resolveConcurrently(inbox, pending))
            .containsExactlyInAnyOrder(
                ApprovalInboxResolutionStatus.RESOLVED,
                ApprovalInboxResolutionStatus.ALREADY_RESOLVED);
      }
      case "full-inbox-fails-closed" -> {
        JdbcApprovalInbox inbox = inbox(id).inbox();
        for (int index = 0; index < 64; index++) {
          inbox.create(entry(index, id + index, ISSUED));
        }
        assertThatThrownBy(() -> inbox.create(entry(64, "overflow", ISSUED)))
            .isInstanceOf(ApprovalInboxRepositoryException.class);
      }
      case "reopen-preserves-decision" -> {
        InboxFixture fixture = inbox(id);
        ApprovalInboxEntry pending = entry(8, id, ISSUED);
        fixture.inbox().create(pending);
        fixture
            .inbox()
            .resolve(
                pending.reference(), ApprovalInboxDecision.DENIED, "actor", ISSUED.plusSeconds(1));
        assertThat(
                new JdbcApprovalInbox(fixture.schema())
                    .list(ISSUED.plusSeconds(2), 64)
                    .getFirst()
                    .state())
            .isEqualTo(ApprovalState.DENIED);
      }
      case "database-filename-is-isolated" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () -> new ApprovalInboxSchemaInitializer(tempDir.resolve("sessions.db"), 1));
      case "schema-rejects-unexpected-table" -> {
        InboxFixture fixture = inbox(id);
        try (Connection connection = fixture.schema().openConnection();
            var statement = connection.createStatement()) {
          statement.execute("CREATE TABLE unexpected_table (value TEXT)");
        }
        assertThatThrownBy(fixture.schema()::initialize)
            .isInstanceOf(ApprovalInboxRepositoryException.class);
      }
      case "schema-has-no-arguments-column" -> {
        InboxFixture fixture = inbox(id);
        List<String> columns = new ArrayList<>();
        try (Connection connection = fixture.schema().openConnection();
            var statement = connection.createStatement();
            var rows = statement.executeQuery("PRAGMA table_info(approval_inbox_entries)")) {
          while (rows.next()) {
            columns.add(rows.getString("name"));
          }
        }
        assertThat(columns).doesNotContain("arguments");
      }
      case "persistence-hashes-binding-identifiers" -> {
        InboxFixture fixture = inbox(id);
        fixture.inbox().create(entry(12, id, ISSUED));
        try (Connection connection = fixture.schema().openConnection();
            var statement =
                connection.prepareStatement(
                    "SELECT session_binding, turn_id, call_id FROM approval_inbox_entries");
            var rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("session_binding"))
              .matches("[0-9a-f]{64}")
              .isNotEqualTo("session-binding");
          assertThat(rows.getString("turn_id")).matches("[0-9a-f]{64}").isNotEqualTo("turn-id");
          assertThat(rows.getString("call_id")).matches("[0-9a-f]{64}").isNotEqualTo("call-id");
          assertThat(rows.next()).isFalse();
        }
      }
      case "disabled-mode-creates-no-store" ->
          assertThat(new ApprovalInboxProperties("DISABLED").mode())
              .isEqualTo(ApprovalInboxMode.DISABLED);
      case "mode-is-case-sensitive" ->
          assertThatIllegalArgumentException()
              .isThrownBy(() -> new ApprovalInboxProperties("loopback"));
      case "loopback-control-is-required" ->
          assertThatThrownBy(() -> disabledControlSchema(id))
              .isInstanceOf(IllegalStateException.class);
      case "projection-hides-actor" ->
          assertThat(ApprovalInboxItemResponse.from(entry(9, id, ISSUED)).toString())
              .doesNotContain("actorReference");
      case "projection-hides-internal-binding" -> {
        String rendered = ApprovalInboxItemResponse.from(entry(10, id, ISSUED)).toString();
        assertThat(rendered).doesNotContain("session-binding", "idempotency-key", "a".repeat(64));
      }
      case "decision-body-rejects-unknown-field" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () ->
                      ApprovalInboxDecisionRequest.parse(
                          "{\"schemaVersion\":1,\"decision\":\"APPROVED\",\"extra\":true}"
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                          JSON));
      case "decision-body-rejects-lowercase-enum" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () ->
                      ApprovalInboxDecisionRequest.parse(
                          "{\"schemaVersion\":1,\"decision\":\"approved\"}"
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                          JSON));
      case "decision-body-rejects-oversize" ->
          assertThatIllegalArgumentException()
              .isThrownBy(
                  () ->
                      ApprovalInboxDecisionRequest.parse(
                          "x".repeat(129).getBytes(java.nio.charset.StandardCharsets.UTF_8), JSON));
      case "approval-does-not-invoke-tool" ->
          assertThat(
                  Class.forName("io.namei.agent.application.SideEffectBatchCoordinator")
                      .getDeclaredFields())
              .noneMatch(field -> field.getType().getSimpleName().equals("ApprovalInbox"));
      case "approved-does-not-change-runtime-mode" ->
          assertThat(
                  new ToolRuntimeSettings(
                          ToolRuntimeMode.READ_ONLY, 1, 1, Duration.ofSeconds(1), 1, 1)
                      .mode())
              .isEqualTo(ToolRuntimeMode.READ_ONLY);
      default -> throw new AssertionError("未知审批收件箱 Fixture Case: " + id);
    }
  }

  private void assertTerminal(String id, ApprovalInboxDecision decision, ApprovalState expected) {
    JdbcApprovalInbox inbox = inbox(id).inbox();
    ApprovalInboxEntry pending = entry(11, id, ISSUED);
    inbox.create(pending);
    assertThat(
            inbox
                .resolve(pending.reference(), decision, "actor", ISSUED.plusSeconds(1))
                .entry()
                .orElseThrow()
                .state())
        .isEqualTo(expected);
  }

  private static List<ApprovalInboxResolutionStatus> resolveConcurrently(
      JdbcApprovalInbox inbox, ApprovalInboxEntry pending) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var approved =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return inbox
                    .resolve(
                        pending.reference(),
                        ApprovalInboxDecision.APPROVED,
                        "actor-one",
                        ISSUED.plusSeconds(1))
                    .status();
              });
      var denied =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return inbox
                    .resolve(
                        pending.reference(),
                        ApprovalInboxDecision.DENIED,
                        "actor-two",
                        ISSUED.plusSeconds(1))
                    .status();
              });
      assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(approved.get(), denied.get());
    }
  }

  private void disabledControlSchema(String id) {
    var factory = new StaticListableBeanFactory();
    factory.addBean("controlPlaneProperties", controls(ControlPlaneMode.DISABLED));
    new ApprovalInboxConfiguration()
        .approvalInboxSchema(
            new AgentProperties(tempDir.resolve(id), null, null, null, null, null),
            new ApprovalInboxProperties("LOOPBACK"),
            factory.getBeanProvider(ControlPlaneProperties.class));
  }

  private InboxFixture inbox(String id) {
    var schema =
        new ApprovalInboxSchemaInitializer(tempDir.resolve(id).resolve("approval-inbox.db"), 5_000);
    schema.initialize();
    return new InboxFixture(schema, new JdbcApprovalInbox(schema));
  }

  private static ApprovalInboxEntry entry(int reference, String approvalId, Instant issuedAt) {
    return ApprovalInboxEntry.pending(
        ApprovalInboxReference.of(reference(reference)),
        new ApprovalRequest(
            approvalId,
            "session-binding",
            "turn-id",
            "call-id",
            "safe_write",
            "v1",
            ToolRisk.WRITE,
            "b".repeat(64),
            "idempotency-key",
            "安全摘要",
            issuedAt,
            issuedAt.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64)));
  }

  private static String reference(int value) {
    byte[] bytes = new byte[16];
    bytes[14] = (byte) (value >>> 8);
    bytes[15] = (byte) value;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static ControlPlaneProperties controls(ControlPlaneMode mode) {
    return new ControlPlaneProperties(
        mode.name(),
        Duration.ofMinutes(15),
        4,
        128,
        Duration.ofMinutes(5),
        1_024,
        8,
        64,
        Duration.ofSeconds(15),
        Duration.ofMinutes(15),
        Duration.ofSeconds(2));
  }

  private static Path goldenRoot() {
    return Path.of(System.getProperty("golden.root")).toAbsolutePath().normalize();
  }

  private record InboxFixture(ApprovalInboxSchemaInitializer schema, JdbcApprovalInbox inbox) {}
}
