package io.namei.agent.bootstrap.control;

import io.namei.agent.application.ApprovalInboxDecision;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record ApprovalInboxDecisionRequest(ApprovalInboxDecision decision) {
  static ApprovalInboxDecisionRequest parse(byte[] body, ObjectMapper json) {
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(json, "json");
    try {
      JsonNode root = json.readTree(body);
      JsonNode schemaVersion = root == null ? null : root.get("schemaVersion");
      JsonNode decision = root == null ? null : root.get("decision");
      if (root == null
          || !root.isObject()
          || root.size() != 2
          || schemaVersion == null
          || !schemaVersion.isIntegralNumber()
          || !schemaVersion.canConvertToInt()
          || schemaVersion.intValue() != 1
          || decision == null
          || !decision.isString()) {
        throw new IllegalArgumentException("审批请求格式无效");
      }
      return new ApprovalInboxDecisionRequest(ApprovalInboxDecision.valueOf(decision.asString()));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("审批请求格式无效");
    }
  }
}
