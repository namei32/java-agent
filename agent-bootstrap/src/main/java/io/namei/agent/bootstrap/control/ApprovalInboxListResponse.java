package io.namei.agent.bootstrap.control;

import io.namei.agent.kernel.control.ControlPlaneContract;
import java.util.List;
import java.util.Objects;

public record ApprovalInboxListResponse(int schemaVersion, List<ApprovalInboxItemResponse> items) {
  ApprovalInboxListResponse(List<ApprovalInboxItemResponse> items) {
    this(ControlPlaneContract.CURRENT_VERSION, List.copyOf(Objects.requireNonNull(items, "items")));
  }
}
