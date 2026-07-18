package io.namei.agent.bootstrap.control;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.namei.agent.application.TurnCancellationSource;
import io.namei.agent.application.control.ControlCancellationHandle;
import io.namei.agent.bootstrap.channel.ChannelHost;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

class ControlPlaneControllerTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void servesAuthenticatedStatusTurnsAndIdempotentTargetCancellation() throws Exception {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var source = new TurnCancellationSource();
    String turnRef =
        runtime
            .register("telegram", ControlCancellationHandle.from(source), NOW)
            .turnRef()
            .orElseThrow()
            .value();
    var host = new ChannelHost(List.of());
    host.start();
    var service =
        new ControlPlaneStatusService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            host,
            runtime,
            ControlPlaneStatusServiceTest.properties());
    var sessions =
        new OperatorSessionStore(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            runtime.eventHub()::closeActor);
    String token = sessions.create().accessToken();
    var filter =
        new ControlPlaneSecurityFilter(
            new LoopbackRequestGuard(),
            sessions,
            ControlPlaneAudit.disabled(),
            () -> "request-control-1",
            new ObjectMapper());
    MockMvc mvc = standaloneSetup(new ControlPlaneController(service)).addFilters(filter).build();

    mvc.perform(authenticated(get("/api/v1/control/status"), token))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.schemaVersion").value(1))
        .andExpect(jsonPath("$.host.state").value("RUNNING"))
        .andExpect(jsonPath("$.control.activeTurns").value(1));

    mvc.perform(authenticated(get("/api/v1/control/turns"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].turnRef").value(turnRef))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("telegram:raw"))));

    mvc.perform(authenticated(post("/api/v1/control/turns/{turnRef}/cancel", turnRef), token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.result").value("CANCELLATION_REQUESTED"))
        .andExpect(jsonPath("$.state").value("CANCELLATION_REQUESTED"));
    mvc.perform(authenticated(post("/api/v1/control/turns/{turnRef}/cancel", turnRef), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("ALREADY_REQUESTED"));
  }

  @Test
  void returnsSafeNotFoundEnvelopeForUnknownCanonicalTurnReference() throws Exception {
    var runtime = ControlPlaneStatusServiceTest.runtime();
    var host = new ChannelHost(List.of());
    host.start();
    var service =
        new ControlPlaneStatusService(
            Clock.fixed(NOW, ZoneOffset.UTC),
            host,
            runtime,
            ControlPlaneStatusServiceTest.properties());
    var sessions =
        new OperatorSessionStore(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new OperatorSessionStoreTest.SequentialRandom(),
            Duration.ofMinutes(15),
            1,
            actor -> {});
    String token = sessions.create().accessToken();
    var filter =
        new ControlPlaneSecurityFilter(
            new LoopbackRequestGuard(),
            sessions,
            ControlPlaneAudit.disabled(),
            () -> "request-control-2",
            new ObjectMapper());
    MockMvc mvc = standaloneSetup(new ControlPlaneController(service)).addFilters(filter).build();

    mvc.perform(
            authenticated(
                post(
                    "/api/v1/control/turns/{turnRef}/cancel",
                    ControlPlaneStatusServiceTest.reference(99).value()),
                token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CONTROL_TURN_NOT_FOUND"))
        .andExpect(jsonPath("$.retryable").value(false))
        .andExpect(jsonPath("$.requestId").value("request-control-2"));
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
