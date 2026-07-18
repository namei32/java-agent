package io.namei.agent.bootstrap.cutover;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CutoverCommandTest {
  @TempDir Path tempDir;

  @Test
  void planRehearseAndVerifyStayInsideSandboxAndNeverPrintItsPathOrContent() throws Exception {
    Path sandbox = tempDir.resolve("sandbox");
    var plan = run("cutover-plan", "--sandbox-root=" + sandbox);
    assertThat(plan.code()).isZero();
    assertThat(plan.output()).contains("DRAFT").doesNotContain(sandbox.toString());

    Path config = sandbox.resolve("input/config/config.toml");
    Files.createDirectories(config.getParent());
    Files.writeString(config, "api-key = 'never-print-me'");

    var rehearsal = run("cutover-rehearse", "--sandbox-root=" + sandbox, "--offline-evidence");
    assertThat(rehearsal.code()).isZero();
    assertThat(rehearsal.output()).contains("READY").doesNotContain("never-print-me");

    var verification = run("cutover-verify", "--sandbox-root=" + sandbox);
    assertThat(verification.code()).isZero();
    assertThat(verification.output()).contains("verified=true").doesNotContain(sandbox.toString());
  }

  @Test
  void rehearsalRequiresExplicitOfflineEvidence() {
    Path sandbox = tempDir.resolve("sandbox");
    run("cutover-plan", "--sandbox-root=" + sandbox);

    var result = run("cutover-rehearse", "--sandbox-root=" + sandbox);

    assertThat(result.code()).isEqualTo(2);
    assertThat(result.output()).contains("PRECONDITION_MISSING");
  }

  private Result run(String... args) {
    var bytes = new ByteArrayOutputStream();
    int code =
        CutoverCommand.run(
            args,
            tempDir.resolve("repository"),
            tempDir.resolve("workspace"),
            tempDir.resolve("home"),
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
    return new Result(code, bytes.toString(StandardCharsets.UTF_8));
  }

  private record Result(int code, String output) {}
}
