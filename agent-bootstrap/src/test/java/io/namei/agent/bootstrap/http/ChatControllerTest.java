package io.namei.agent.bootstrap.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.namei.agent.adapter.sqlite.SqliteRepositoryException;
import io.namei.agent.application.ChatCommand;
import io.namei.agent.application.ChatResult;
import io.namei.agent.application.ChatUseCase;
import io.namei.agent.application.SessionLockTimeoutException;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.model.ChatMessage;
import io.namei.agent.kernel.model.MessageRole;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatController.class)
@Import({
  ApiExceptionHandler.class,
  RequestIdFilter.class,
  ContentLengthLimitFilter.class,
  ChatControllerTest.StubConfiguration.class
})
class ChatControllerTest {
  @Autowired MockMvc mvc;
  @Autowired StubChatUseCase useCase;

  @BeforeEach
  void resetUseCase() {
    useCase.result = new ChatResult("demo", new ChatMessage(MessageRole.ASSISTANT, "默认回答"));
    useCase.failure = null;
    useCase.command = null;
  }

  @Test
  void returnsAssistantMessageAndPreservesValidRequestId() throws Exception {
    useCase.result = new ChatResult("demo", new ChatMessage(MessageRole.ASSISTANT, "回答"));

    mvc.perform(
            post("/api/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header(RequestIdFilter.HEADER, "request-1")
                .content("{\"sessionId\":\"demo\",\"message\":\"  问题  \"}"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(RequestIdFilter.HEADER, "request-1"))
        .andExpect(jsonPath("$.sessionId").value("demo"))
        .andExpect(jsonPath("$.message.role").value("assistant"))
        .andExpect(jsonPath("$.message.content").value("回答"));

    assertThat(useCase.command).isEqualTo(new ChatCommand("demo", "问题"));
  }

  @Test
  void rejectsInvalidInputAndMalformedJsonWithGeneratedRequestIds() throws Exception {
    mvc.perform(
            post("/api/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header(RequestIdFilter.HEADER, "bad request id")
                .content("{\"sessionId\":\"../bad\",\"message\":\"问题\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(header().exists(RequestIdFilter.HEADER))
        .andExpect(
            header()
                .string(
                    RequestIdFilter.HEADER,
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo("bad request id"))))
        .andExpect(jsonPath("$.title").value("请求参数无效"));

    mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(header().exists(RequestIdFilter.HEADER))
        .andExpect(jsonPath("$.title").value("JSON 格式无效"));

    mvc.perform(
            post("/api/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"demo\",\"message\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("请求参数无效"));
  }

  @Test
  void rejectsDeclaredOversizedRequestBody() throws Exception {
    String oversized = "{\"sessionId\":\"demo\",\"message\":\"" + "x".repeat(65_536) + "\"}";

    mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(oversized))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(header().exists(RequestIdFilter.HEADER))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("请求体过大"));
  }

  @Test
  void rejectsOversizedRequestWhenContentLengthIsUnknown() throws Exception {
    var rawRequest = new MockHttpServletRequest();
    rawRequest.setRequestURI("/api/v1/chat");
    rawRequest.setContent("x".repeat(65_537).getBytes(StandardCharsets.UTF_8));
    var unknownLengthRequest =
        new HttpServletRequestWrapper(rawRequest) {
          @Override
          public int getContentLength() {
            return -1;
          }

          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    var response = new MockHttpServletResponse();
    var chainInvoked = new AtomicBoolean();

    new ContentLengthLimitFilter()
        .doFilter(unknownLengthRequest, response, (request, result) -> chainInvoked.set(true));

    assertThat(chainInvoked).isFalse();
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(
            MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getContentType())))
        .isTrue();
    assertThat(response.getContentAsString()).contains("请求体过大");
  }

  @Test
  void mapsModelFailuresToBadGatewayWithoutLeakingCause() throws Exception {
    useCase.failure =
        new ModelInvocationException("模型调用失败", new IllegalStateException("Bearer provider-secret"));

    performValidChat()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("模型调用失败"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("provider-secret"))));

    useCase.failure = new InvalidModelResponseException("raw provider payload");
    performValidChat()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("模型调用失败"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("raw provider payload"))));
  }

  @Test
  void mapsModelAndSessionTimeoutsToGatewayTimeout() throws Exception {
    useCase.failure = new ModelTimeoutException("模型调用超时", new RuntimeException("secret"));
    performValidChat()
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.title").value("请求执行超时"));

    useCase.failure = new SessionLockTimeoutException("demo");
    performValidChat()
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.title").value("请求执行超时"));
  }

  @Test
  void mapsPersistenceAndUnexpectedFailuresToInternalServerError() throws Exception {
    useCase.failure = new SqliteRepositoryException("database-secret");
    performValidChat()
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.title").value("会话持久化失败"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("database-secret"))));

    useCase.failure = new IllegalStateException("internal-secret");
    performValidChat()
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.title").value("内部服务错误"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal-secret"))));
  }

  private org.springframework.test.web.servlet.ResultActions performValidChat() throws Exception {
    return mvc.perform(
        post("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sessionId\":\"demo\",\"message\":\"问题\"}"));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class StubConfiguration {
    @Bean
    StubChatUseCase chatUseCase() {
      return new StubChatUseCase();
    }
  }

  static final class StubChatUseCase implements ChatUseCase {
    private ChatResult result;
    private RuntimeException failure;
    private ChatCommand command;

    @Override
    public ChatResult chat(ChatCommand command) {
      this.command = command;
      if (failure != null) {
        throw failure;
      }
      return result;
    }
  }
}
