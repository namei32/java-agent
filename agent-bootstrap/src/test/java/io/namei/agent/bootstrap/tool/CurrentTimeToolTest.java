package io.namei.agent.bootstrap.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.namei.agent.kernel.tool.ToolResultStatus;
import io.namei.agent.kernel.tool.ToolRisk;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CurrentTimeToolTest {
  @Test
  void exposesReadOnlySchemaAndReturnsClockTime() {
    var tool =
        new CurrentTimeTool(
            Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC));

    assertThat(tool.definition().name()).isEqualTo("current_time");
    assertThat(tool.definition().risk()).isEqualTo(ToolRisk.READ_ONLY);
    assertThat(tool.definition().inputSchema().get("type")).isEqualTo("object");
    assertThat(tool.execute(Map.of()).status()).isEqualTo(ToolResultStatus.SUCCESS);
    assertThat(tool.execute(Map.of()).content()).isEqualTo("2026-07-13T00:00Z");
    assertThat(tool.execute(Map.of("unexpected", true)).status())
        .isEqualTo(ToolResultStatus.ERROR);
  }
}
