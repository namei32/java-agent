package io.namei.agent.bootstrap.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/memories")
public class MemoryController {
  private static final String SAFE_IDENTIFIER = "[A-Za-z0-9_-]+";

  private final MemoryManagementApi api;

  public MemoryController(MemoryManagementApi api) {
    this.api = Objects.requireNonNull(api, "api");
  }

  @PutMapping
  ResponseEntity<MemoryWriteHttpResponse> write(
      @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_IDENTIFIER) String sessionId,
      @Valid @RequestBody MemoryWriteHttpRequest request) {
    var outcome = api.write(sessionId, request.toApplicationRequest());
    HttpStatus status =
        switch (outcome.status()) {
          case CREATED -> HttpStatus.CREATED;
          case REINFORCED -> HttpStatus.OK;
        };
    return ResponseEntity.status(status)
        .body(new MemoryWriteHttpResponse(outcome.status(), outcome.memory()));
  }

  @GetMapping
  MemoryListHttpResponse list(
      @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_IDENTIFIER)
          String sessionId) {
    return new MemoryListHttpResponse(api.list(sessionId));
  }

  @DeleteMapping("/{memoryId}")
  MemoryDeleteHttpResponse delete(
      @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_IDENTIFIER) String sessionId,
      @PathVariable @NotBlank @Size(max = 128) @Pattern(regexp = SAFE_IDENTIFIER) String memoryId,
      @RequestHeader("Idempotency-Key")
          @NotBlank
          @Size(max = 128)
          @Pattern(regexp = SAFE_IDENTIFIER)
          String requestId) {
    var outcome = api.delete(sessionId, requestId, memoryId);
    return switch (outcome.status()) {
      case DELETED -> new MemoryDeleteHttpResponse(outcome.status(), outcome.itemId());
      case NOT_FOUND -> throw new MemoryNotFoundException();
    };
  }
}
