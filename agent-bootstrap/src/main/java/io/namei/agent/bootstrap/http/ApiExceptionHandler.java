package io.namei.agent.bootstrap.http;

import io.namei.agent.adapter.sqlite.SqliteRepositoryException;
import io.namei.agent.application.ApprovalUnavailableException;
import io.namei.agent.application.SessionLockTimeoutException;
import io.namei.agent.application.SideEffectStateUnknownException;
import io.namei.agent.kernel.error.InvalidModelResponseException;
import io.namei.agent.kernel.error.ModelInvocationException;
import io.namei.agent.kernel.error.ModelTimeoutException;
import io.namei.agent.kernel.error.ToolCallLimitExceededException;
import io.namei.agent.kernel.error.ToolLoopLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "请求参数无效", request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail malformedJson(HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "JSON 格式无效", request);
  }

  @ExceptionHandler({
    ModelInvocationException.class,
    InvalidModelResponseException.class,
    ToolCallLimitExceededException.class,
    ToolLoopLimitExceededException.class,
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
