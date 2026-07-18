package io.namei.agent.bootstrap.control;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.namei.agent.adapter.sqlite.ApprovalInboxSchemaInitializer;
import io.namei.agent.adapter.sqlite.JdbcApprovalInbox;
import io.namei.agent.application.ApprovalInboxEntry;
import io.namei.agent.application.ApprovalInboxReference;
import io.namei.agent.kernel.approval.ApprovalRequest;
import io.namei.agent.kernel.tool.ToolRisk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

class ApprovalInboxControllerTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @TempDir Path tempDir;

  @Test
  void localOperatorCanListAndResolveSafeApprovalProjectionOnly() throws Exception {
    JdbcApprovalInbox inbox = inbox();
    inbox.create(pending());
    var sessions =
        new OperatorSessionStore(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            actor -> {});
    String token = sessions.create().accessToken();
    var audit = ControlPlaneAudit.disabled();
    var filter =
        new ControlPlaneSecurityFilter(
            new LoopbackRequestGuard(),
            sessions,
            audit,
            () -> "approval-request",
            new ObjectMapper());
    MockMvc mvc =
        standaloneSetup(
                new ApprovalInboxController(
                    new ApprovalInboxControlService(Clock.fixed(NOW, ZoneOffset.UTC), inbox),
                    audit,
                    new ObjectMapper()))
            .addFilters(filter)
            .build();

    mvc.perform(authenticated(get("/api/v1/control/approvals"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion").value(1))
        .andExpect(jsonPath("$.items[0].approvalRef").value("AQEBAQEBAQEBAQEBAQEBAQ"))
        .andExpect(jsonPath("$.items[0].summary").value("安全摘要"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("b".repeat(64)))));

    mvc.perform(
            authenticated(
                    post(
                        "/api/v1/control/approvals/{approvalRef}/decisions",
                        "AQEBAQEBAQEBAQEBAQEBAQ"),
                    token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":1,\"decision\":\"APPROVED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("APPROVED"))
        .andExpect(jsonPath("$.actorReference").doesNotExist());

    mvc.perform(
            authenticated(
                    post(
                        "/api/v1/control/approvals/{approvalRef}/decisions",
                        "AQEBAQEBAQEBAQEBAQEBAQ"),
                    token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":1,\"decision\":\"APPROVED\",\"actor\":\"forbidden\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("APPROVAL_REQUEST_INVALID"));

    mvc.perform(
            authenticated(
                    post(
                        "/api/v1/control/approvals/{approvalRef}/decisions",
                        "AQEBAQEBAQEBAQEBAQEBAQ"),
                    token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(129)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("APPROVAL_REQUEST_INVALID"));
  }

  private JdbcApprovalInbox inbox() {
    var schema = new ApprovalInboxSchemaInitializer(tempDir.resolve("approval-inbox.db"), 5_000);
    schema.initialize();
    return new JdbcApprovalInbox(schema);
  }

  private static ApprovalInboxEntry pending() {
    return ApprovalInboxEntry.pending(
        ApprovalInboxReference.of("AQEBAQEBAQEBAQEBAQEBAQ"),
        new ApprovalRequest(
            "approval-1",
            "session-binding",
            "turn-id",
            "call-id",
            "safe_write",
            "v1",
            ToolRisk.WRITE,
            "b".repeat(64),
            "idempotency-key",
            "安全摘要",
            NOW,
            NOW.plusSeconds(300),
            ApprovalRequest.FINGERPRINT_VERSION,
            "a".repeat(64)));
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      authenticated(
          org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
          String token) {
    return request
        .with(
            value -> {
              value.setRemoteAddr("127.0.0.1");
              value.setScheme("http");
              return value;
            })
        .header("Host", "127.0.0.1:8080")
        .header("Authorization", "Bearer " + token);
  }
}
