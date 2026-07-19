package io.namei.agent.bootstrap.http;

import io.namei.agent.adapter.sqlite.SqliteRepositoryException;
import io.namei.agent.application.ApprovalUnavailableException;
import io.namei.agent.application.MemoryContextUnavailableException;
import io.namei.agent.application.SessionLockTimeoutException;
import io.namei.agent.application.SideEffectStateUnknownException;
import io.namei.agent.kernel.error.EmbeddingInvocationException;
import io.namei.agent.kernel.error.InvalidEmbeddingResponseException;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelContextLimitException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelSafetyRejectedException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import io.namei.agent.kernel.memory.MemoryIdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    MissingRequestValueException.class,
    InvalidMemoryRequestException.class
  })
  ProblemDetail validation(HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "请求参数无效", request);
  }

  @ExceptionHandler(MemoryIdempotencyConflictException.class)
  ProblemDetail memoryConflict(HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "记忆请求幂等冲突", request);
  }

  @ExceptionHandler({EmbeddingInvocationException.class, InvalidEmbeddingResponseException.class})
  ProblemDetail memoryEmbedding(HttpServletRequest request) {
    return problem(HttpStatus.BAD_GATEWAY, "记忆 Embedding 失败", request);
  }

  @ExceptionHandler(MemoryApiPersistenceException.class)
  ProblemDetail memoryPersistence(HttpServletRequest request) {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "记忆持久化失败", request);
  }

  @ExceptionHandler(MemoryApiUnavailableException.class)
  ProblemDetail memoryUnavailable(HttpServletRequest request) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "记忆功能不可用", request);
  }

  @ExceptionHandler(MemoryNotFoundException.class)
  ProblemDetail memoryNotFound(HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "记忆不存在", request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail malformedJson(HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "JSON 格式无效", request);
  }

  @ExceptionHandler({
    ModelInvocationException.class,
    ModelSafetyRejectedException.class,
    ModelContextLimitException.class,
    InvalidModelResponseException.class,
    ToolCallLimitExceededException.class,
    ToolLoopLimitExceededException.class,
    MemoryContextUnavailableException.class,
    ApprovalUnavailableException.class,
    SideEffectStateUnknownException.class
  })
  ProblemDetail modelFailure(HttpServletRequest request) {
    return problem(HttpStatus.BAD_GATEWAY, "模型调用失败", request);
  }

  @ExceptionHandler({ModelTimeoutException.class, SessionLockTimeoutException.class})
  ProblemDetail timeout(HttpServletRequest request) {
    return problem(HttpStatus.GATEWAY_TIMEOUT, "请求执行超时", request);
  }

  @ExceptionHandler(SqliteRepositoryException.class)
  ProblemDetail persistence(HttpServletRequest request) {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "会话持久化失败", request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ProblemDetail notFound(HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "资源不存在", request);
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(HttpServletRequest request) {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "内部服务错误", request);
  }

  private static ProblemDetail problem(
      HttpStatus status, String title, HttpServletRequest request) {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, title);
    detail.setType(URI.create("about:blank"));
    detail.setTitle(title);
    detail.setInstance(URI.create(request.getRequestURI()));
    return detail;
  }
}
