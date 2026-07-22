package io.namei.agent.bootstrap.cutover;

import io.namei.agent.adapter.workspace.cutover.SandboxCutoverAdapter;
import io.namei.agent.adapter.workspace.cutover.SandboxCutoverException;
import io.namei.agent.application.CutoverRehearsalService;
import io.namei.agent.kernel.cutover.CutoverContractViolation;
import io.namei.agent.kernel.cutover.CutoverMode;
import io.namei.agent.kernel.cutover.CutoverStableCode;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Spring 启动前的离线命令入口。它没有网络、配置、Provider 或进程控制路径。 */
public final class CutoverCommand {
  private static final String PLAN = "cutover-plan";
  private static final String REHEARSE = "cutover-rehearse";
  private static final String VERIFY = "cutover-verify";
  private static final String SANDBOX_PREFIX = "--sandbox-root=";
  private static final String OFFLINE_EVIDENCE = "--offline-evidence";

  private CutoverCommand() {}

  public static boolean isRequested(String[] args) {
    for (String argument : Objects.requireNonNull(args, "args")) {
      if (PLAN.equals(argument) || REHEARSE.equals(argument) || VERIFY.equals(argument)) {
        return true;
      }
    }
    return false;
  }

  public static int run(
      String[] args, Path repositoryRoot, Path configuredWorkspace, Path home, PrintStream output) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(output, "output");
    try {
      String command = command(args);
      Path sandboxRoot =
          sandboxRoot(args).orElseThrow(() -> new IllegalArgumentException("缺少 sandbox-root"));
      if (PLAN.equals(command)) {
        SandboxCutoverAdapter.createNew(sandboxRoot, repositoryRoot, configuredWorkspace, home);
        output.println("mode=PLAN_ONLY state=DRAFT");
        return 0;
      }
      var sandbox =
          SandboxCutoverAdapter.openExisting(
              sandboxRoot, repositoryRoot, configuredWorkspace, home);
      if (REHEARSE.equals(command)) {
        boolean evidence = has(args, OFFLINE_EVIDENCE);
        var report =
            new CutoverRehearsalService(sandbox)
                .rehearse(CutoverMode.REHEARSAL, hash(sandboxRoot), evidence);
        if (!report.eligibility().eligible()) {
          output.println("state=DRAFT code=" + CutoverStableCode.PRECONDITION_MISSING);
          return 2;
        }
        output.println(
            "mode=REHEARSAL state="
                + report.plan().state()
                + " backup="
                + report.manifest().orElseThrow().backupId()
                + " difference="
                + report.difference().orElseThrow().changedEntries());
        return 0;
      }
      if (VERIFY.equals(command)) {
        var manifest = sandbox.latestManifest();
        boolean verified = sandbox.verify(manifest);
        var difference = sandbox.compare(manifest, 0);
        output.println(
            "mode=REHEARSAL verified="
                + verified
                + " difference="
                + difference.changedEntries()
                + " withinThreshold="
                + difference.withinThreshold());
        return verified && difference.withinThreshold() ? 0 : 2;
      }
      return invalid(output);
    } catch (SandboxCutoverException exception) {
      output.println("code=" + exception.code());
      return 2;
    } catch (CutoverContractViolation exception) {
      output.println("code=" + exception.code());
      return 2;
    } catch (IllegalArgumentException | IllegalStateException exception) {
      output.println("code=" + CutoverStableCode.CUTOVER_CONTRACT_INVALID);
      return 2;
    }
  }

  private static String command(String[] args) {
    String selected = null;
    for (String argument : args) {
      if (PLAN.equals(argument) || REHEARSE.equals(argument) || VERIFY.equals(argument)) {
        if (selected != null) {
          throw new IllegalArgumentException("多个 cutover command");
        }
        selected = argument;
      }
    }
    if (selected == null) {
      throw new IllegalArgumentException("缺少 cutover command");
    }
    return selected;
  }

  private static Optional<Path> sandboxRoot(String[] args) {
    Path selected = null;
    for (String argument : args) {
      if (argument.startsWith(SANDBOX_PREFIX)) {
        if (selected != null) {
          throw new IllegalArgumentException("sandbox-root 重复");
        }
        String value = argument.substring(SANDBOX_PREFIX.length());
        if (value.isBlank()) {
          throw new IllegalArgumentException("sandbox-root 为空");
        }
        selected = Path.of(value);
      }
    }
    return Optional.ofNullable(selected);
  }

  private static boolean has(String[] args, String expected) {
    for (String argument : args) {
      if (expected.equals(argument)) {
        return true;
      }
    }
    return false;
  }

  private static int invalid(PrintStream output) {
    output.println("code=" + CutoverStableCode.CUTOVER_CONTRACT_INVALID);
    return 2;
  }

  private static String hash(Path value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      value
                          .toAbsolutePath()
                          .normalize()
                          .toString()
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }
}
