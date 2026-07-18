package io.namei.agent.kernel.cutover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CutoverContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SANDBOX = "a".repeat(64);

  @Test
  void consumesEveryJavaOwnedCutoverFixtureCase() throws Exception {
    JsonNode fixture = JSON.readTree(goldenRoot().resolve("cutover/cutover-runtime-v1.json"));
    for (JsonNode testCase : fixture.path("cases")) {
      String id = testCase.path("id").asString();
      JsonNode input = testCase.path("input");
      JsonNode expected = testCase.path("expected");
      if (input.path("operation").asString().equals("mode")) {
        assertOutcome(
            expected,
            () ->
                new CutoverPlan(
                    CutoverMode.parse(input.path("mode").asString()), SANDBOX, CutoverState.DRAFT),
            id);
      } else if (input.path("operation").asString().equals("transition")) {
        assertOutcome(expected, () -> transition(input), id);
      } else if (input.path("operation").asString().equals("eligibility")) {
        var eligibility = new CutoverEligibility(Set.of(CutoverStableCode.PRECONDITION_MISSING));
        assertThat(eligibility.eligible()).isEqualTo(expected.path("eligible").asBoolean());
      } else {
        throw new AssertionError("未知 Fixture operation: " + id);
      }
    }
  }

  private static CutoverPlan transition(JsonNode input) {
    var plan = new CutoverPlan(CutoverMode.PLAN_ONLY, SANDBOX, CutoverState.DRAFT);
    for (JsonNode state : input.path("states")) {
      plan =
          switch (state.asText()) {
            case "ELIGIBLE" -> plan.withEligibility(CutoverEligibility.passing());
            case "BACKED_UP" -> plan.markBackedUp();
            case "REHEARSED" -> plan.markRehearsed();
            case "READY" -> plan.markReady();
            case "CUTTING_OVER" -> plan.markCuttingOver();
            default -> throw new AssertionError(state.asText());
          };
    }
    return plan;
  }

  private static void assertOutcome(JsonNode expected, ThrowingSupplier<?> operation, String id) {
    if (expected.path("accepted").asBoolean()) {
      assertThatCode(() -> operation.get()).as(id).doesNotThrowAnyException();
      return;
    }
    assertThatThrownBy(operation::get)
        .as(id)
        .isInstanceOf(CutoverContractViolation.class)
        .extracting(error -> ((CutoverContractViolation) error).code().name())
        .isEqualTo(expected.path("code").asString());
  }

  private static Path goldenRoot() {
    String configured = System.getProperty("golden.root");
    assertThat(configured).as("Maven 必须提供 golden.root").isNotBlank();
    return Path.of(configured).toAbsolutePath().normalize();
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get();
  }
}
