package io.namei.agent.bootstrap.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.namei.agent.kernel.control.HistoryDetailItem;
import io.namei.agent.kernel.control.HistoryDetailPage;
import io.namei.agent.kernel.control.HistoryScopeCapability;
import io.namei.agent.kernel.control.HistoryVisibleRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

class ControlHistoryDetailControllerTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
  private static final String ACTOR = "A".repeat(22);
  private static final HistoryScopeCapability SCOPE =
      HistoryScopeCapability.fromTrustedDigest("a".repeat(64));

  @Test
  void mapsOnlyTheApprovedGetShapeAndKeepsDetailMetadataSecretFree() throws Exception {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var sessions = sessions(runtime);
    String token = sessions.create().accessToken();
    var detail = detail(runtime);
    var auditEvents = new ArrayList<ControlAuditEvent>();
    var audit = new ControlPlaneAudit(Clock.fixed(NOW, ZoneOffset.UTC), auditEvents::add);
    var statusService =
        new ControlPlaneStatusService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new io.namei.agent.bootstrap.channel.ChannelHost(List.of()),
            runtime,
            ControlPlaneStatusServiceTest.properties());
    var filter =
        new ControlPlaneSecurityFilter(
            new LoopbackRequestGuard(),
            sessions,
            audit,
            () -> "request-history-detail",
            new ObjectMapper());
    MockMvc mvc =
        standaloneSetup(new ControlPlaneController(statusService, audit, detail))
            .addFilters(filter)
            .build();

    var issued =
        mvc.perform(authenticated(get("/api/v1/control/history/detail"), token))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.state").value("REFERENCE_ISSUED"))
            .andExpect(jsonPath("$.detailRef").isString())
            .andReturn();
    String reference =
        new ObjectMapper()
            .readTree(issued.getResponse().getContentAsString())
            .path("detailRef")
            .asString();
    ControlAuditEvent issuedAudit =
        auditEvents.stream()
            .filter(event -> event.action().equals("HISTORY_DETAIL"))
            .findFirst()
            .orElseThrow();
    assertThat(issuedAudit)
        .extracting(ControlAuditEvent::result, ControlAuditEvent::code, ControlAuditEvent::count)
        .containsExactly("REFERENCE_ISSUED", "", 0L);
    assertThat(issuedAudit.actorHash()).matches("[A-Za-z0-9_-]{22}");
    assertThat(issuedAudit.turnHash()).matches("[A-Za-z0-9_-]{22}").isNotEqualTo(reference);
    assertThat(issuedAudit.toString()).doesNotContain(reference, token);

    mvc.perform(
            authenticated(
                get("/api/v1/control/history/detail").queryParam("ref", reference), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"))
        .andExpect(jsonPath("$.items[0].role").value("USER"))
        .andExpect(jsonPath("$.items[0].content").doesNotExist())
        .andExpect(jsonPath("$.actor").doesNotExist())
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());

    mvc.perform(
            authenticated(
                get("/api/v1/control/history/detail").queryParam("ref", reference), token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CONTROL_HISTORY_NOT_FOUND"));

    mvc.perform(
            authenticated(
                get("/api/v1/control/history/detail")
                    .queryParam("ref", reference)
                    .queryParam("cursor", reference),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONTROL_REQUEST_INVALID"));

    mvc.perform(
            authenticated(
                get("/api/v1/control/history/detail").queryParam("pageSize", "1", "2"), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONTROL_REQUEST_INVALID"));

    mvc.perform(
            authenticated(
                get("/api/v1/control/history/detail").queryParam("sessionId", "raw-session"),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONTROL_REQUEST_INVALID"));
  }

  @Test
  void neverLetsAuditSinkFailureChangeTheSafeDetailResponse() throws Exception {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var sessions = sessions(runtime);
    String token = sessions.create().accessToken();
    var statusService =
        new ControlPlaneStatusService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new io.namei.agent.bootstrap.channel.ChannelHost(List.of()),
            runtime,
            ControlPlaneStatusServiceTest.properties());
    var audit =
        new ControlPlaneAudit(
            Clock.fixed(NOW, ZoneOffset.UTC),
            event -> {
              throw new IllegalStateException("audit-private-detail-reference");
            });
    MockMvc mvc =
        standaloneSetup(new ControlPlaneController(statusService, audit, detail(runtime)))
            .addFilters(
                new ControlPlaneSecurityFilter(
                    new LoopbackRequestGuard(),
                    sessions,
                    audit,
                    () -> "request-history-detail-audit-failure",
                    new ObjectMapper()))
            .build();

    mvc.perform(authenticated(get("/api/v1/control/history/detail"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("REFERENCE_ISSUED"));
  }

  private static OperatorSessionStore sessions(ControlPlaneRuntime runtime) {
    return new OperatorSessionStore(
        Clock.fixed(NOW, ZoneOffset.UTC),
        size -> {
          byte[] bytes = new byte[size];
          bytes[bytes.length - 1] = 1;
          return bytes;
        },
        Duration.ofMinutes(15),
        4,
        runtime.eventHub()::closeActor);
  }

  private static ControlHistoryDetailService detail(ControlPlaneRuntime runtime) {
    List<HistoryDetailItem> items =
        List.of(new HistoryDetailItem(HistoryVisibleRole.USER, NOW.minusSeconds(1)));
    var sequence = new AtomicInteger();
    return new ControlHistoryDetailService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        runtime,
        actor -> java.util.Optional.of(SCOPE),
        (scope, request) -> new HistoryDetailPage(items, true),
        size -> {
          byte[] bytes = new byte[size];
          bytes[bytes.length - 1] = (byte) sequence.incrementAndGet();
          return bytes;
        });
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
